;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.core.session
  "Agent-session management.

   An agent-session is the identity/state scope shared between a root agent and
   its sub-agent family. It carries:
   - :user-id and :session-id (agent-session-id) — identity
   - :messages — conversation history
   - :data — progress state (traces, todo-info, artifacts)
   - :agent-activity — sub-agent display events
   - :config — session-level config (permission-fn, usage-tracker, dirs, etc.)
   - :total-turns — monotonic cumulative ask counter across the root agent
                    and every sub-agent in this agent-session. Used as the
                    audit row index. Per-agent `:turn-id` lives on each
                    agent's `:st-memory-init` instead.

   Agent instances do NOT hold :user-id / :session-id as record fields —
   they are read from the shared session atom via the proto/user-id and
   proto/session-id accessors. This is the 'agent-session' layer in the
   two-layer session model; the app/bridge layer (TUI tab, WebSocket connection)
   is tracked separately by each base.

   Uses an in-memory atom-backed store by default.
   Protocol-based design allows future Redis/SQLite backends."
  (:require [ai.brainyard.agent.core.hooks :as hooks]))

;; ============================================================================
;; Session Store Protocol
;; ============================================================================

(defprotocol ISessionStore
  "Protocol for pluggable session storage backends.
   The store holds session atoms — get-session returns an atom (or nil),
   and set-session registers an atom in the store."
  (get-session [this session-id] "Retrieve session atom by ID, or nil")
  (set-session [this session-id !session] "Register a session atom in the store")
  (delete-session [this session-id]
    "Remove a session from the store. Implementations should fire
     `:agent.session/closed` when an existing session is deleted.")
  (list-sessions [this user-id] "List session data maps for a user"))

;; ============================================================================
;; In-Memory Session Store
;; ============================================================================

(defrecord InMemorySessionStore [!store]
  ISessionStore
  (get-session [_ session-id]
    (get @!store session-id))            ;; returns atom or nil
  (set-session [_ session-id !session]
    (swap! !store assoc session-id !session)
    !session)                            ;; returns the atom
  (delete-session [_ session-id]
    (when-let [!s (get @!store session-id)]
      (swap! !store dissoc session-id)
      (hooks/fire! :agent.session/closed
                   {:session-id session-id
                    :user-id    (:user-id @!s)
                    :session    !s}))
    nil)
  (list-sessions [_ user-id]
    (->> @!store
         vals
         (filter #(= user-id (:user-id @%)))  ;; deref atoms to filter
         (mapv deref))))

(defn create-session-store
  "Create an in-memory session store."
  []
  (->InMemorySessionStore (atom {})))

(defn generate-session-id
  "Generate a unique session-id string with the given prefix."
  ([] (generate-session-id "agt"))
  ([prefix] (str prefix "-" (System/currentTimeMillis) "-" (rand-int 10000))))

;; ============================================================================
;; Session Data Helpers
;; ============================================================================

(defn create-session
  "Create a new session data map."
  [session-id user-id & {:keys [config] :or {config {}}}]
  {:session-id session-id
   :user-id user-id
   :config config
   :messages []
   :data {:traces [] :todo-info nil :artifacts [] :cache-hits 0 :exceptions []}
   :agent-activity []
   :agent-activity-seq 0
   :total-turns 0
   :created-at (System/currentTimeMillis)
   :updated-at (System/currentTimeMillis)})

(defn get-or-create-session
  "Get existing session atom from store, or create and register a new one.
   Returns the session atom. If no store is provided, creates a standalone atom.

   Fires `:agent.session/created` only on the new-session path. Resuming an
   existing session is the responsibility of a dedicated resume entry point
   (see `resume-session`), not this getter."
  [session-store session-id user-id & {:keys [config] :or {config {}}}]
  (if session-store
    (or (get-session session-store session-id)
        (let [!s (atom (create-session session-id user-id :config config))]
          (set-session session-store session-id !s)
          (hooks/fire! :agent.session/created
                       {:session-id session-id :user-id user-id :session !s})
          !s))
    (let [!s (atom (create-session session-id user-id :config config))]
      (hooks/fire! :agent.session/created
                   {:session-id session-id :user-id user-id :session !s})
      !s)))

(defn inc-total-turns!
  "Increment and return the session's :total-turns counter — the
   cumulative count of `ask` invocations across the root agent and
   every sub-agent in this agent-session.

   This counter is the audit row index in `memory_audit` (combined
   with :session-id and :agent-id), so it must remain monotonic and
   shared across every agent that operates in this session."
  [!session]
  (:total-turns (swap! !session update :total-turns (fnil inc 0))))

(defn add-message
  "Add a message to session history."
  [session message]
  (-> session
      (update :messages conj message)
      (assoc :updated-at (System/currentTimeMillis))))

(defn get-messages
  "Get session messages, optionally limited to last n."
  ([session] (:messages session))
  ([session n]
   (let [msgs (:messages session)]
     (if (> (count msgs) n)
       (vec (take-last n msgs))
       msgs))))

(defn update-data
  "Update progress/data state for a session (formerly update-thinking).

   Sub-keys under [:data ...]:
   - :trace       - BT execution trace entry
   - :todo-info   - Todo list update
   - :artifact    - Artifact to display
   - :cache-hit   - Semantic cache hit
   - :exception   - Exception info
   - :user-action - User action request/response"
  [session data]
  (let [session (assoc session :updated-at (System/currentTimeMillis))]
    (cond-> session
      (:trace data)
      (update-in [:data :traces] conj (:trace data))

      (:todo-info data)
      (assoc-in [:data :todo-info] (:todo-info data))

      (:artifact data)
      (update-in [:data :artifacts] conj (:artifact data))

      (:cache-hit data)
      (update-in [:data :cache-hits] inc)

      (:exception data)
      (update-in [:data :exceptions] conj (:exception data))

      (:user-action data)
      (assoc-in [:data :user-action] (:user-action data)))))

(defn clear-data
  "Clear progress/data state for a new run (formerly clear-thinking)."
  [session]
  (-> session
      (assoc :data
             {:traces [] :todo-info nil :artifacts [] :cache-hits 0 :exceptions []})
      (assoc :agent-activity [] :agent-activity-seq 0)))

(defn append-agent-activity
  "Append a sub-agent display event to the session activity stream.
   Each entry gets an incrementing :seq for robust diffing.
   Caps at 200 entries to prevent unbounded growth."
  [session entry]
  (let [seq-num (inc (or (:agent-activity-seq session) 0))
        entry (assoc entry :seq seq-num)
        activity (conj (or (:agent-activity session) []) entry)
        activity (if (> (count activity) 200)
                   (subvec activity (- (count activity) 200))
                   activity)]
    (assoc session
           :agent-activity activity
           :agent-activity-seq seq-num
           :updated-at (System/currentTimeMillis))))

(defn get-session-config
  "Get a config value from session."
  [session key]
  (get-in session [:config key]))

(defn set-session-config
  "Set a config value in session."
  [session key value]
  (assoc-in session [:config key] value))

;; ============================================================================
;; Message Types
;; ============================================================================

(defn system-message
  "Create a system message."
  [content]
  {:role "system" :content content})

(defn user-message
  "Create a user message."
  [content]
  {:role "user" :content content})

(defn assistant-message
  "Create an assistant message, optionally tagged with an agent-id and a
   `kind` distinguishing the two assistant shapes that land in a shared
   session: `:turn-answer` (an agent's final answer for a turn) vs
   `:dispatch` (a sub-agent's input recorded under the parent's id). The
   conversation timeline transform needs the distinction — both carry
   the same agent-id; messages from sessions persisted before this tag
   fall back to a structural heuristic (see context-actions)."
  ([content]
   {:role "assistant" :content content})
  ([content agent-id]
   {:role "assistant" :content content :agent-id agent-id})
  ([content agent-id kind]
   {:role "assistant" :content content :agent-id agent-id :kind kind}))
