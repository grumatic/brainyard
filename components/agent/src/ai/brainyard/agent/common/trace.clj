;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.trace
  "Mulog-based tracing helpers for agent execution.
   Replaces cloudcast's Grain event-store tracing with structured mulog events.

   Provides:
   - add-trace-event — log trace via mulog
   - default-maintain-conversation — BT action recording question/answer
   - get-maintain-conversation-fn — factory for conversation actions"
  (:require [ai.brainyard.mulog.interface :as mulog]
            [ai.brainyard.behavior-tree.interface :as bt]
            [ai.brainyard.agent.core.protocol :as proto]))

;; ============================================================================
;; Trace Events
;; ============================================================================

(defn add-trace-event
  "Log a trace event via mulog.
   In cloudcast this appended to Grain event store; here we use mulog structured logging.

   Parameters:
     agent - Agent instance (for agent-id, user-id, session-id)
     trace - Trace data map"
  [agent trace]
  (mulog/log ::agent-trace
             :agent-id (proto/agent-id agent)
             :user-id (proto/user-id agent)
             :session-id (proto/session-id agent)
             :trace trace))

;; ============================================================================
;; Conversation Maintenance (BT Actions)
;; ============================================================================

(defn get-maintain-conversation-fn
  "Create a BT action function that records conversation messages.
   Replaces cloudcast's es-ext/get-maintain-conversation-fn.

   Parameters:
     request-key - Key in st-memory for the user's input (e.g. :question)
     reply-key   - Key in st-memory for the agent's reply (e.g. :answer)

   Options:
     :request-prefix - Prefix for request message
     :reply-prefix   - Prefix for reply message

   Returns: BT action function (fn [{:keys [st-memory agent]}] ...)"
  [request-key reply-key
   & {:keys [request-prefix reply-prefix]}]
  (let [bt-success bt/success]
    (fn [{:keys [st-memory agent] :as _context}]
      (let [request (when request-key (get @st-memory request-key))
            reply (when reply-key (get @st-memory reply-key))]
        ;; Log conversation via mulog
        (when (or request reply)
          (mulog/log ::agent-conversation
                     :agent-id (when agent (proto/agent-id agent))
                     :user-id (when agent (proto/user-id agent))
                     :session-id (when agent (proto/session-id agent))
                     :request (when request (str request-prefix request))
                     :reply (when reply (str reply-prefix reply))))
        bt-success))))

(def default-maintain-conversation
  "Default BT action that records :question and :answer from st-memory."
  (get-maintain-conversation-fn :question :answer))

(def add-question-in-conversation
  "BT action that records only :question from st-memory."
  (get-maintain-conversation-fn :question nil))

(def add-answer-in-conversation
  "BT action that records only :answer from st-memory."
  (get-maintain-conversation-fn nil :answer))
