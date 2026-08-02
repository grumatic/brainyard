;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a-client.core.client
  "The A2A client RPC surface: build params, call, unwrap.

   Every function takes a `peer` record (see `make-peer`) and returns a
   brainyard-shaped map — `{:result …}` / `{:answer …}` on success,
   `{:error …}` on failure. Nothing throws.

   ## This namespace does not know what a brainyard agent is

   `send-message!` takes an opaque `:metadata` map and puts it on the wire
   verbatim. It deliberately does NOT reach for the cross-process call
   chain itself, because that lives in `agent.core.protocol` and requiring
   it here would create an `a2a-client -> agent` dependency — the same
   dependency `acp-client/core/events.clj` exists to avoid. The agent layer
   stamps the chain and passes it down (Phase 3 of
   docs/design/a2a-design.md)."
  (:require [clojure.string :as str]
            [ai.brainyard.a2a.interface :as a2a]
            [ai.brainyard.a2a-client.core.auth :as auth]
            [ai.brainyard.a2a-client.core.transport :as transport])
  (:import [java.util UUID]))

;; =============================================================================
;; Peer record
;; =============================================================================

(defn make-peer
  "Build a peer record — the handle every call in this namespace takes.

   `:endpoint` is resolved from the card when not given explicitly, so a
   caller normally supplies only `:name`, `:url`, `:card` and `:auth`.
   Each peer gets its OWN id source, so concurrent peers never share a
   request-id counter."
  [{:keys [name url card auth endpoint timeout-ms stream-timeout-ms]}]
  {:name              (str name)
   :url               (a2a/base-url url)
   :endpoint          (or endpoint
                          (some-> card a2a/jsonrpc-endpoint)
                          (a2a/base-url url))
   :card              card
   :auth              (auth/normalize auth)
   :timeout-ms        (or timeout-ms transport/DEFAULT_TIMEOUT_MS)
   :stream-timeout-ms (or stream-timeout-ms transport/DEFAULT_STREAM_TIMEOUT_MS)
   :next-id           (a2a/make-id-source)})

(defn describe-peer
  "A redaction-safe summary of a peer, for `a2a$list`, logs and LLM context.
   Never carries the credential — see `auth/redact`."
  [peer]
  {:name       (:name peer)
   :url        (:url peer)
   :endpoint   (:endpoint peer)
   :auth       (auth/describe (:auth peer))
   :agent-name (some-> peer :card :name)
   :skills     (mapv :id (a2a/card-skills (:card peer)))
   :streaming  (a2a/card-supports? (:card peer) :streaming)})

;; =============================================================================
;; Message construction
;; =============================================================================

(defn user-message
  "Build an A2A user Message carrying `text`.

   `:metadata` rides through untouched — it is where the caller puts the
   cross-process call chain."
  [text & {:keys [task-id context-id metadata parts]}]
  (cond-> {:messageId (str (UUID/randomUUID))
           :role      "user"
           :kind      "message"
           :parts     (or parts [(a2a/text-part text)])}
    task-id           (assoc :taskId task-id)
    context-id        (assoc :contextId context-id)
    (seq metadata)    (assoc :metadata metadata)))

(defn- send-params
  [message {:keys [blocking? history-length accepted-output-modes push-config]}]
  (let [config (cond-> {}
                 (some? blocking?)            (assoc :blocking (boolean blocking?))
                 (some? history-length)       (assoc :historyLength history-length)
                 (seq accepted-output-modes)  (assoc :acceptedOutputModes
                                                     (vec accepted-output-modes))
                 push-config                  (assoc :pushNotificationConfig push-config))]
    (cond-> {:message message}
      (seq config) (assoc :configuration config))))

;; =============================================================================
;; Result normalization
;; =============================================================================

(defn result->outcome
  "Normalize a `message/send` result into a uniform outcome map.

   A2A lets a server answer with EITHER a `Task` (work was created) or a
   bare `Message` (answered inline, no task). Callers should not have to
   branch on that, so both collapse to:

     {:answer     <text>
      :task-id    <id or nil>
      :context-id <id or nil>
      :state      <keyword task state>   ; :completed for a bare Message
      :artifacts  [<artifact> …]
      :raw        <the original object>}

   The `:state` for a bare Message is `:completed` because an inline answer
   IS the finished work — there is nothing left to poll."
  [result]
  (let [kind (:kind result)]
    (cond
      (nil? result)
      {:error "empty result from peer"}

      ;; A Task: carries :status. Check the shape rather than trusting
      ;; :kind, which some servers omit.
      (or (= "task" kind) (contains? result :status))
      (let [state (a2a/state->kw (get-in result [:status :state]))]
        {:answer     (or (some-> (get-in result [:status :message]) a2a/message-text)
                         (->> (:artifacts result)
                              (mapcat :parts)
                              a2a/parts-text))
         :task-id    (:id result)
         :context-id (:contextId result)
         :state      state
         :artifacts  (vec (:artifacts result))
         :raw        result})

      ;; A bare Message: answered inline.
      (or (= "message" kind) (contains? result :parts))
      {:answer     (a2a/message-text result)
       :task-id    (:taskId result)
       :context-id (:contextId result)
       :state      :completed
       :artifacts  []
       :raw        result}

      :else
      {:error (str "unrecognized A2A result shape: " (pr-str (keys result)))
       :raw   result})))

;; =============================================================================
;; Methods
;; =============================================================================

(defn send-message!
  "`message/send`. Returns a normalized outcome (see `result->outcome`) or
   `{:error …}`.

   `:blocking?` maps to A2A's send configuration: true asks the server to
   hold the response until the task reaches a terminal or interrupted
   state; false returns as soon as the task is created. Leave it unset to
   take the server's default."
  [peer text & {:as opts}]
  (let [msg (user-message text
                          :task-id (:task-id opts)
                          :context-id (:context-id opts)
                          :metadata (:metadata opts)
                          :parts (:parts opts))
        {:keys [result error] :as res} (transport/rpc! peer :message-send
                                                       (send-params msg opts))]
    (if error res (result->outcome result))))

(defn stream-message!
  "`message/stream`. Returns `{:stop! fn :running? fn}`, or `{:error …}`
   when the peer does not advertise streaming.

   Refusing up front on a non-streaming card is deliberate: the alternative
   is a round trip that comes back `UnsupportedOperationError` telling us
   what the card already said."
  [peer text handlers & {:as opts}]
  (if-not (a2a/card-supports? (:card peer) :streaming)
    {:error (str "peer '" (:name peer) "' does not advertise streaming"
                 " — use send-message! instead")}
    (let [msg (user-message text
                            :task-id (:task-id opts)
                            :context-id (:context-id opts)
                            :metadata (:metadata opts)
                            :parts (:parts opts))]
      (transport/open-sse! peer :message-stream (send-params msg opts) handlers))))

(defn get-task
  "`tasks/get`. Returns `{:task …}` or `{:error …}`.

   `:history-length` 0 means \"omit history\"; omitting the option means
   \"server default\". They are different requests, so nil is not coerced
   to 0."
  [peer task-id & {:keys [history-length]}]
  (let [params (cond-> {:id task-id}
                 (some? history-length) (assoc :historyLength history-length))
        {:keys [result error] :as res} (transport/rpc! peer :tasks-get params)]
    (if error res {:task result})))

(defn task-state
  "The current state of a remote task as a keyword, or `{:error …}`.
   The polling primitive the Phase-4 task executor is built on."
  [peer task-id]
  (let [{:keys [task error] :as res} (get-task peer task-id :history-length 0)]
    (if error res {:state (a2a/state->kw (get-in task [:status :state]))
                   :task  task})))

(defn cancel-task!
  "`tasks/cancel`. Returns `{:task …}` or `{:error …}`.

   A `TaskNotCancelableError` is a normal outcome, not a bug: the task
   already reached a terminal state before our cancel arrived."
  [peer task-id]
  (let [{:keys [result error] :as res} (transport/rpc! peer :tasks-cancel
                                                       {:id task-id})]
    (if error res {:task result})))

(defn list-tasks
  "`tasks/list`. Optional in A2A — a server may answer
   `UnsupportedOperationError`, which surfaces as an ordinary `{:error …}`."
  [peer & {:keys [context-id page-size page-token history-length]}]
  (let [params (cond-> {}
                 context-id     (assoc :contextId context-id)
                 page-size      (assoc :pageSize page-size)
                 page-token     (assoc :pageToken page-token)
                 (some? history-length) (assoc :historyLength history-length))
        {:keys [result error] :as res} (transport/rpc! peer :tasks-list params)]
    (if error res {:tasks (:tasks result) :next-page-token (:nextPageToken result)})))

(defn resubscribe!
  "`tasks/resubscribe` — reattach an SSE stream to an EXISTING task, e.g.
   after a dropped connection. Same handler contract as `stream-message!`."
  [peer task-id handlers]
  (if-not (a2a/card-supports? (:card peer) :streaming)
    {:error (str "peer '" (:name peer) "' does not advertise streaming")}
    (transport/open-sse! peer :tasks-resubscribe {:id task-id} handlers)))

;; =============================================================================
;; Push notification configuration
;; =============================================================================

(defn set-push-config!
  "`tasks/pushNotificationConfig/set`. Gated on the card's
   `pushNotifications` capability."
  [peer task-id config]
  (if-not (a2a/card-supports? (:card peer) :pushNotifications)
    {:error (str "peer '" (:name peer) "' does not advertise push notifications")}
    (let [{:keys [result error] :as res}
          (transport/rpc! peer :push-config-set
                          {:taskId task-id :pushNotificationConfig config})]
      (if error res {:config result}))))

(defn get-push-config
  "`tasks/pushNotificationConfig/get`."
  [peer task-id config-id]
  (let [{:keys [result error] :as res}
        (transport/rpc! peer :push-config-get
                        (cond-> {:id task-id}
                          config-id (assoc :pushNotificationConfigId config-id)))]
    (if error res {:config result})))

(defn list-push-configs
  "`tasks/pushNotificationConfig/list`."
  [peer task-id]
  (let [{:keys [result error] :as res}
        (transport/rpc! peer :push-config-list {:id task-id})]
    (if error res {:configs result})))

(defn delete-push-config!
  "`tasks/pushNotificationConfig/delete`. Idempotent per spec."
  [peer task-id config-id]
  (let [{:keys [error] :as res}
        (transport/rpc! peer :push-config-delete
                        {:id task-id :pushNotificationConfigId config-id})]
    (if error res {:deleted true})))

;; =============================================================================
;; Skill addressing
;; =============================================================================

(defn skill-prompt
  "Compose the text sent to a peer for a specific skill.

   A2A has no `skill` field on `message/send` — a card advertises skills,
   but the wire carries only a message. Routing to one is therefore a
   convention, and ours is an explicit prefix line. This is lossy by
   nature; it is called out here rather than hidden so nobody later
   mistakes it for a protocol guarantee."
  [skill-id text]
  (if (str/blank? (str skill-id))
    text
    (str "[skill: " skill-id "]\n" text)))
