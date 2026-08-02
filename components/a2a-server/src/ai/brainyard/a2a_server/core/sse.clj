;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a-server.core.sse
  "Server-Sent Events for `message/stream` and `tasks/resubscribe`.

   A2A streams JSON-RPC RESPONSES, not bare objects: each frame's `data:`
   payload is a full `{jsonrpc, id, result}` envelope whose `result` is a
   `StreamResponse`. Emitting bare objects would parse on a lenient client
   and fail on a strict one, so the envelope is built here rather than left
   to callers.

   Frame sequence for one turn:

       task            (submitted)   — so the client learns the task id at once
       status-update   (working)     — one per chunk the service reports
       status-update   (terminal, final:true)

   The `submitted` frame first matters: without it a client that dropped
   mid-turn has no id to `tasks/resubscribe` with.

   ## Honest scope

   Fine-grained progress depends entirely on the service calling
   `:on-chunk`. When it does not, a turn yields exactly the three frames
   above — still conformant SSE (ordered frames, stream closes on a
   terminal state), just not incremental. That is stated plainly rather
   than described as progressive streaming it does not deliver."
  (:require [clojure.string :as str]
            [ai.brainyard.a2a.interface :as a2a]
            [ai.brainyard.a2a-server.core.handlers :as handlers]
            [ai.brainyard.mulog.interface :as mulog])
  (:import [java.util UUID]))

(defn frame
  "Encode one JSON-RPC result as an SSE frame, terminator included."
  [id result]
  (str "data: " (a2a/encode (a2a/response id result)) "\n\n"))

(defn error-frame
  "Encode a JSON-RPC error response as an SSE frame."
  [error-response]
  (str "data: " (a2a/encode error-response) "\n\n"))

(defn comment-frame
  "An SSE comment — a keep-alive that carries no data and must be ignored
   by any conformant client."
  [text]
  (str ": " text "\n\n"))

(defn- status-frame
  [id task-id context-id state & {:keys [text final]}]
  (frame id
         (cond-> {:taskId task-id
                  :kind   "status-update"
                  :status (cond-> {:state (a2a/kw->state state)}
                            (not (str/blank? (str text)))
                            (assoc :message
                                   {:messageId (str (UUID/randomUUID))
                                    :role "agent"
                                    :parts [(a2a/text-part text)]}))}
           context-id (assoc :contextId context-id)
           final      (assoc :final true))))

(defn stream-turn!
  "Run one `message/stream` turn, writing SSE frames via `write!`.

   `write!` is `(fn [^String s])`; it may throw when the client
   disconnects, and that is treated as an orderly stop rather than an
   error — a client hanging up mid-stream is normal.

   Returns `{:frames n}` or `{:error …}`."
  [{:keys [ask-fn max-depth] :as _service} id params write!]
  (let [msg        (:message params)
        text       (a2a/message-text msg)
        metadata   (:metadata msg)
        context-id (:contextId msg)
        task-id    (or (:taskId msg) (str (UUID/randomUUID)))
        !frames    (atom 0)
        !dead      (atom false)
        emit!      (fn [s]
                     (when-not @!dead
                       (try (write! s) (swap! !frames inc)
                            (catch Throwable _
                              ;; Client went away. Stop writing, but let the
                              ;; turn finish — the work may still be worth
                              ;; completing for a later tasks/get.
                              (reset! !dead true)))))]
    (cond
      (str/blank? (str text))
      (do (emit! (error-frame (a2a/error-invalid-params
                               id "message must carry at least one text part")))
          {:frames @!frames})

      :else
      (if-let [refusal (handlers/check-chain! id metadata max-depth)]
        ;; Refuse BEFORE any work — the guard exists to avoid spending the
        ;; turn, so emitting the refusal and stopping is the whole point.
        (do (emit! (error-frame refusal))
            {:frames @!frames :refused true})

        (do
          (emit! (frame id {:id task-id :kind "task"
                            :contextId context-id
                            :status {:state "submitted"}}))
          (let [out (try
                      (ask-fn {:text text :context-id context-id :task-id task-id
                               :metadata metadata
                               :on-chunk (fn [_delta accumulated]
                                           (emit! (status-frame id task-id context-id
                                                                :working
                                                                :text accumulated)))})
                      (catch Throwable t
                        (mulog/error ::stream-ask-failed :exception t)
                        {:error (ex-message t)}))]
            (if (:error out)
              (do (emit! (status-frame id task-id context-id :failed
                                       :text (:error out) :final true))
                  {:frames @!frames :error (:error out)})
              (do (emit! (status-frame id task-id context-id
                                       (or (:state out) :completed)
                                       :text (:answer out)
                                       ;; An INTERRUPTED state is not final:
                                       ;; the task stays open awaiting the
                                       ;; client, and marking it final would
                                       ;; tell them to stop listening.
                                       :final (not (a2a/interrupted?
                                                    (or (:state out) :completed)))))
                  {:frames @!frames}))))))))

(defn resubscribe!
  "`tasks/resubscribe` — reattach to an existing task.

   Emits the task's current state and, when it is already terminal, closes
   immediately. We do not replay the frames a client missed: nothing is
   retained to replay, and inventing a synthetic history would misrepresent
   what happened."
  [{:keys [get-task-fn] :as _service} id params write!]
  (let [task-id (:id params)]
    (cond
      (nil? get-task-fn)
      (do (write! (error-frame (a2a/error-unsupported id "tasks/resubscribe")))
          {:frames 1})

      :else
      (if-let [task (get-task-fn task-id)]
        (do (write! (frame id task))
            {:frames 1})
        (do (write! (error-frame (a2a/error-not-found id)))
            {:frames 1})))))
