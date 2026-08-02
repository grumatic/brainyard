;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a-server.core.handlers
  "JSON-RPC method dispatch for the A2A server.

   ## Dependency injection, not a dependency on `agent`

   This component knows nothing about brainyard agents. Every capability
   arrives as a function in a `service` map, supplied by the caller
   (`components/agent`'s serve wiring). That keeps `a2a-server` pure
   transport — the same split as `acp` (pure protocol) vs `acp-client`
   (lifecycle) — and, just as usefully, makes the whole surface testable
   against a stub service with no agent runtime at all.

       {:card-fn       (fn [] <AgentCard>)                    ; required
        :ask-fn        (fn [req] -> outcome | {:error …})     ; required
        :get-task-fn   (fn [task-id] -> <Task> | nil)         ; optional
        :cancel-fn     (fn [task-id] -> <Task> | {:error …})  ; optional
        :list-tasks-fn (fn [opts] -> {:tasks [] :next-page-token s}) ; optional
        :max-depth     3}

   `ask-fn` receives
   `{:text :skill-id :context-id :task-id :metadata :on-chunk}` and returns
   `{:answer :task-id :context-id :state :artifacts}` or `{:error …}`.
   `:on-chunk` is a `(fn [delta accumulated])` the service may call to
   stream progress; it is a no-op on the non-streaming path.

   ## The cycle guard runs FIRST

   `message/send` and `message/stream` check the inbound call chain before
   touching `ask-fn`. Refusing after spending an LLM turn would defeat the
   entire point of the guard."
  (:require [clojure.string :as str]
            [ai.brainyard.a2a.interface :as a2a]
            [ai.brainyard.mulog.interface :as mulog])
  (:import [java.util UUID]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- ok [id result] (a2a/response id result))

(defn- optional-op
  "Error response for a method this server does not implement. A2A says an
   unimplemented optional method answers UnsupportedOperationError, NOT
   MethodNotFound — the method exists in the protocol, we just do not offer
   it."
  [id what]
  (a2a/error-unsupported id what))

(defn- agent-message
  "Build an agent-role Message carrying `text`."
  [text & {:keys [task-id context-id]}]
  (cond-> {:messageId (str (UUID/randomUUID))
           :role      "agent"
           :kind      "message"
           :parts     [(a2a/text-part (str text))]}
    task-id    (assoc :taskId task-id)
    context-id (assoc :contextId context-id)))

(defn- task-response
  "Build a Task object for an outcome."
  [{:keys [answer task-id context-id state artifacts]}]
  (let [tid (or task-id (str (UUID/randomUUID)))]
    (cond-> {:id     tid
             :kind   "task"
             :status (cond-> {:state (a2a/kw->state (or state :completed))}
                       (not (str/blank? (str answer)))
                       (assoc :message (agent-message answer :task-id tid
                                                      :context-id context-id)))}
      context-id     (assoc :contextId context-id)
      (seq artifacts) (assoc :artifacts (vec artifacts)))))

(defn- extract-request
  "Pull the fields `ask-fn` needs out of `message/send` params."
  [params]
  (let [msg (:message params)]
    {:text       (a2a/message-text msg)
     :context-id (:contextId msg)
     :task-id    (:taskId msg)
     :metadata   (:metadata msg)
     :parts      (:parts msg)}))

;; =============================================================================
;; The chain guard
;; =============================================================================

(defn check-chain!
  "Nil when the request may proceed, else a JSON-RPC error response.

   Mapped to `UnsupportedOperationError` because A2A has no dedicated code
   for 'I refuse to recurse'; the `data.detail` carries the real reason so a
   brainyard caller can tell the two apart."
  [id metadata max-depth]
  (when-let [{:keys [error reason]} (a2a/check-chain {:metadata metadata
                                                      :max-depth max-depth})]
    (mulog/warn ::chain-refused :reason reason
                :chain (a2a/describe-chain metadata))
    (a2a/error-unsupported id error)))

;; =============================================================================
;; Method handlers
;; =============================================================================

(defn handle-message-send
  "`message/send` — the workhorse. Returns a Task."
  [{:keys [ask-fn max-depth] :as _service} id params]
  (let [{:keys [text metadata] :as req} (extract-request params)]
    (cond
      (str/blank? (str text))
      (a2a/error-invalid-params id "message must carry at least one text part")

      :else
      (or (check-chain! id metadata max-depth)
          (let [out (ask-fn (assoc req :on-chunk (fn [_ _] nil)))]
            (if (:error out)
              ;; A local failure is an agent-side error, not a malformed
              ;; request — InvalidAgentResponse would blame the caller.
              (a2a/error-internal id (:error out))
              (ok id (task-response out))))))))

(defn handle-tasks-get
  [{:keys [get-task-fn] :as _service} id params]
  (cond
    (nil? get-task-fn) (optional-op id "tasks/get")
    (str/blank? (str (:id params))) (a2a/error-invalid-params id ":id is required")
    :else
    (if-let [task (get-task-fn (:id params))]
      (ok id task)
      ;; Missing and unauthorized MUST be indistinguishable, or the error
      ;; channel becomes an enumeration oracle. `error-not-found` takes no
      ;; resource identity by construction.
      (a2a/error-not-found id))))

(defn handle-tasks-cancel
  [{:keys [cancel-fn] :as _service} id params]
  (cond
    (nil? cancel-fn) (optional-op id "tasks/cancel")
    (str/blank? (str (:id params))) (a2a/error-invalid-params id ":id is required")
    :else
    (let [r (cancel-fn (:id params))]
      (cond
        (nil? r)      (a2a/error-not-found id)
        (:error r)    (a2a/error->jsonrpc id :task-not-cancelable {:detail (:error r)})
        :else         (ok id r)))))

(defn handle-tasks-list
  [{:keys [list-tasks-fn] :as _service} id params]
  (if (nil? list-tasks-fn)
    (optional-op id "tasks/list")
    (let [{:keys [tasks next-page-token]} (list-tasks-fn params)]
      (ok id (cond-> {:tasks (vec tasks)}
               next-page-token (assoc :nextPageToken next-page-token))))))

(defn handle-extended-card
  [{:keys [extended-card-fn] :as _service} id _params]
  (if (nil? extended-card-fn)
    (a2a/error->jsonrpc id :extended-card-not-configured)
    (ok id (extended-card-fn))))

;; =============================================================================
;; Dispatch
;; =============================================================================

(def ^:private streaming-methods
  #{"message/stream" "tasks/resubscribe"})

(defn streaming-method?
  "True for methods answered with an SSE stream rather than one JSON body.
   The HTTP layer needs to know before it writes response headers."
  [method]
  (contains? streaming-methods (str method)))

(defn dispatch
  "Route one decoded JSON-RPC request to its handler. Returns a JSON-RPC
   response map.

   Streaming methods are NOT routed here — `core/sse.clj` owns those,
   because they answer with a frame sequence rather than one body."
  [service msg]
  (let [id     (:id msg)
        method (:method msg)
        params (or (:params msg) {})]
    (try
      (case (str method)
        "message/send"                          (handle-message-send service id params)
        "tasks/get"                             (handle-tasks-get service id params)
        "tasks/cancel"                          (handle-tasks-cancel service id params)
        "tasks/list"                            (handle-tasks-list service id params)
        "agent/getAuthenticatedExtendedCard"    (handle-extended-card service id params)

        ;; The push-notification family is declared but not implemented.
        ;; The card advertises pushNotifications:false, so a conformant
        ;; client will not call these; answering Unsupported is the
        ;; spec-correct reply if one does anyway.
        ("tasks/pushNotificationConfig/set"
         "tasks/pushNotificationConfig/get"
         "tasks/pushNotificationConfig/list"
         "tasks/pushNotificationConfig/delete") (optional-op id "push notifications")

        (a2a/error->jsonrpc id :method-not-found {:detail (str method)}))
      (catch Throwable t
        ;; A handler bug must not take down the listener, and must not leak
        ;; a stack trace to a remote caller.
        (mulog/error ::handler-failed :method method :exception t)
        (a2a/error-internal id (ex-message t))))))
