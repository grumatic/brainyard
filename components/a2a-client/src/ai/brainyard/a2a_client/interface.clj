;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a-client.interface
  "Public API for the A2A client — brainyard as CALLER.

   Layers:

   - **Peers** — `connect!` / `disconnect!` / `get-peer` / `list-peers`.
     A peer is a validated Agent Card plus a resolved JSON-RPC endpoint
     and credentials.
   - **Calls** — `send-message!`, `stream-message!`, `get-task`,
     `cancel-task!`, `resubscribe!`, and the push-notification family.
   - **Translation** — `translate` turns stream frames into brainyard hook
     descriptors, without this component knowing what an agent is.

   Everything returns brainyard-shaped maps: `{:error …}` on failure,
   never an exception. A remote peer being down, slow, unauthorized or
   malformed is an ordinary outcome here.

   ## What this component does NOT do

   It does not require `components/agent`, and must not start. The
   cross-process call chain is stamped by the agent layer and passed down
   as an opaque `:metadata` map; stream frames come back out as
   descriptors for the agent layer to fire. Both boundaries exist to keep
   the dependency arrow pointing one way — the same arrangement
   `acp-client` uses.

   See docs/design/a2a-design.md §5.2."
  (:require [ai.brainyard.a2a-client.core.auth :as auth]
            [ai.brainyard.a2a-client.core.client :as client]
            [ai.brainyard.a2a-client.core.discovery :as discovery]
            [ai.brainyard.a2a-client.core.events :as events]
            [ai.brainyard.a2a-client.core.registry :as registry]
            [ai.brainyard.a2a-client.core.transport :as transport]))

;; =============================================================================
;; Peers
;; =============================================================================

(defn connect!
  "Discover, validate and register an A2A peer.
   Opts: `{:name :url :auth :timeout-ms :stream-timeout-ms :refresh?}`.
   Returns `{:peer <summary> :card <card>}` or `{:error …}`."
  [opts]
  (registry/connect! opts))

(defn disconnect!
  "Forget a peer. Does NOT cancel in-flight subscriptions — those are owned
   by whoever opened them."
  [name]
  (registry/disconnect! name))

(defn get-peer
  "Live peer record for `name`, or nil."
  [name]
  (registry/get-peer name))

(defn list-peers
  "All live peer records (carry credentials — do not log)."
  []
  (registry/list-peers))

(defn describe-peers
  "Redaction-safe peer summaries — safe for logs and LLM context."
  []
  (registry/describe-peers))

(defn describe-peer
  "Redaction-safe summary of one peer record."
  [peer]
  (client/describe-peer peer))

(defn register-peer!
  "Insert a prebuilt peer record (tests; callers that already hold a card)."
  [peer]
  (registry/register-peer! peer))

(defn reset-peers!
  "Forget every peer. Tests and session teardown."
  []
  (registry/reset-peers!))

(defn seed-peers!
  "Connect every peer in a config map. Best-effort; returns
   `{:connected [names] :failed {name error}}`."
  [peers-config]
  (registry/seed-peers! peers-config))

(defn resolve-skill
  "Resolve peer + skill to `{:peer :skill :agent-id}` or `{:error …}`.
   `:agent-id` is the URL-scoped call-chain token."
  [peer-name skill-id]
  (registry/resolve-skill peer-name skill-id))

(def ^{:doc "Regex peer names must match — they become tool-id segments."}
  peer-name-re registry/peer-name-re)

;; =============================================================================
;; Discovery
;; =============================================================================

(defn fetch-card!
  "Fetch + validate a peer's public Agent Card. Honours the TTL cache
   unless `:refresh? true`."
  [url & {:as opts}]
  (apply discovery/fetch-card! url (mapcat identity opts)))

(defn fetch-extended-card!
  "Fetch the authenticated extended Agent Card. Refuses when the public
   card does not advertise the capability."
  [peer]
  (discovery/fetch-extended-card! peer))

(defn invalidate-card-cache!
  "Drop a cached card (all when called with no args)."
  ([] (discovery/invalidate!))
  ([url] (discovery/invalidate! url)))

;; =============================================================================
;; Calls
;; =============================================================================

(defn make-peer
  "Build a peer record without registering it."
  [opts]
  (client/make-peer opts))

(defn send-message!
  "`message/send`. Returns a normalized outcome
   (`{:answer :task-id :context-id :state :artifacts :raw}`) or `{:error …}`."
  [peer text & {:as opts}]
  (apply client/send-message! peer text (mapcat identity opts)))

(defn stream-message!
  "`message/stream`. Returns `{:stop! fn :running? fn}` or `{:error …}`.

   `handlers` is `{:on-event f :on-error f :on-close f}`. `:on-event` runs
   INLINE on the reader thread — that is deliberate backpressure, so it
   must not block indefinitely."
  [peer text handlers & {:as opts}]
  (apply client/stream-message! peer text handlers (mapcat identity opts)))

(defn get-task
  "`tasks/get`. Returns `{:task …}` or `{:error …}`."
  [peer task-id & {:as opts}]
  (apply client/get-task peer task-id (mapcat identity opts)))

(defn task-state
  "Current state of a remote task as a keyword. The polling primitive the
   task executor is built on."
  [peer task-id]
  (client/task-state peer task-id))

(defn cancel-task!
  "`tasks/cancel`. A not-cancelable error is a normal outcome."
  [peer task-id]
  (client/cancel-task! peer task-id))

(defn list-tasks
  "`tasks/list`. Optional in A2A; may answer UnsupportedOperationError."
  [peer & {:as opts}]
  (apply client/list-tasks peer (mapcat identity opts)))

(defn resubscribe!
  "`tasks/resubscribe` — reattach a stream to an existing task."
  [peer task-id handlers]
  (client/resubscribe! peer task-id handlers))

(defn set-push-config!    [peer task-id config]    (client/set-push-config! peer task-id config))
(defn get-push-config     [peer task-id config-id] (client/get-push-config peer task-id config-id))
(defn list-push-configs   [peer task-id]           (client/list-push-configs peer task-id))
(defn delete-push-config! [peer task-id config-id] (client/delete-push-config! peer task-id config-id))

(defn skill-prompt
  "Compose the text addressed to one skill. A2A has no `skill` field on
   the wire, so routing is a prefix convention, not a protocol guarantee."
  [skill-id text]
  (client/skill-prompt skill-id text))

(defn result->outcome
  "Normalize a `message/send` result (Task OR bare Message) into a uniform
   outcome map."
  [result]
  (client/result->outcome result))

;; =============================================================================
;; Event translation
;; =============================================================================

(defn initial-acc
  "A fresh translation accumulator."
  []
  (events/initial-acc))

(defn translate
  "Fold one stream payload into `acc`. Returns `{:acc … :events [descriptor …]}`.
   Pure — the caller fires the hooks."
  [acc payload]
  (events/translate acc payload))

(defn translate-all
  "Fold a sequence of payloads. Convenience for tests and batch replay."
  [payloads]
  (events/translate-all payloads))

(defn frame-kind
  "Classify a stream frame. Falls back to shape when `:kind` is absent."
  [frame]
  (events/frame-kind frame))

(def ^{:doc "Hook keyword for streamed text (a real brainyard hook)."}
  event-dspy-chunk events/event-dspy-chunk)
(def ^{:doc "Descriptor keyword for a remote artifact update."}
  event-artifact events/event-artifact)
(def ^{:doc "Descriptor keyword for a remote task state change."}
  event-task-state events/event-task-state)
(def ^{:doc "Descriptor keyword: peer is awaiting client input."}
  event-input-required events/event-input-required)
(def ^{:doc "Descriptor keyword: peer is awaiting credentials."}
  event-auth-required events/event-auth-required)
(def ^{:doc "Descriptor keyword: remote task reached a terminal state."}
  event-terminal events/event-terminal)
(def ^{:doc "Every descriptor keyword this component can emit."}
  all-events events/all-events)

;; =============================================================================
;; Auth (redaction-safe helpers)
;; =============================================================================

(defn normalize-auth
  "Coerce a user-supplied auth spec to canonical form, or nil."
  [spec]
  (auth/normalize spec))

(defn redact-auth
  "`auth` with every secret replaced. The ONLY shape safe to log or show."
  [spec]
  (auth/redact spec))

(defn describe-auth
  "One-word description of the configured scheme. Never the secret."
  [spec]
  (auth/describe spec))

(defn auth-configured?
  "True when the spec carries usable credentials."
  [spec]
  (auth/configured? spec))

;; =============================================================================
;; Transport (exposed for tests and the server-side SSE parser)
;; =============================================================================

(defn parse-sse-line
  "Fold one SSE line into a frame. Returns `[acc' dispatch?]`."
  [acc line]
  (transport/parse-sse-line acc line))

(defn sse-frame-payload
  "The `data` payload of a completed SSE frame."
  [frame]
  (transport/frame-payload frame))

(defn request-headers
  "Headers for an A2A request: content negotiation, version, credentials.
   Pass `:dialect` — the `A2A-Version` value is per-peer, not global."
  [auth-spec & {:as opts}]
  (apply transport/request-headers auth-spec (mapcat identity opts)))

(def ^{:doc "Default whole-request timeout for a blocking RPC (ms)."}
  DEFAULT_TIMEOUT_MS transport/DEFAULT_TIMEOUT_MS)
(def ^{:doc "Default whole-exchange cap for an SSE subscription (ms). Large on
             purpose — the RPC timeout would kill a healthy long stream."}
  DEFAULT_STREAM_TIMEOUT_MS transport/DEFAULT_STREAM_TIMEOUT_MS)
