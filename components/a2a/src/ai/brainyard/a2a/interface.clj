;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a.interface
  "Public API for the A2A (Agent2Agent Protocol) component.

   Three layers, intentionally narrow:

   - **Protocol constants** — method names, the version literal, the
     well-known discovery path, and the wire enumerations.
   - **Schemas** for A2A payloads (Part, Message, Task, Artifact, the
     streaming events, Agent Card), plus wire<->keyword coercion.
   - **Agent Card** construction, parsing, endpoint resolution, and
     version negotiation.
   - **Errors** — the A2A catalog, translated both ways between JSON-RPC
     error objects and brainyard's `{:error \"…\"}` convention.

   This component is **pure protocol plumbing**: pure data, no I/O, no
   agent semantics. HTTP and SSE live in `components/a2a-client`; the
   listener lives in `components/a2a-server`; the runtime wiring lives in
   `components/agent`. See docs/design/a2a-design.md.

   ## JSON-RPC comes from the ACP component

   A2A and ACP are sibling protocols — neither is a layer of the other —
   but JSON-RPC 2.0 is JSON-RPC 2.0, and `ai.brainyard.acp.interface`
   already exports a generic, native-image-proven codec. Depending on it
   is deliberate; duplicating a codec would be worse than an awkward
   dependency arrow. If the coupling ever needs undoing, extract
   `components/clj-jsonrpc` and point both at it — a rename, not a
   rewrite. See docs/design/a2a-design.md §5.1.

   Re-exported here (`request`, `notification`, `response`,
   `error-response`, `encode`, `decode`, `classify`, `make-id-source`) so
   downstream components need only one require."
  (:require [ai.brainyard.acp.interface :as acp]
            [ai.brainyard.a2a.core.card :as card]
            [ai.brainyard.a2a.core.chain :as chain]
            [ai.brainyard.a2a.core.errors :as errors]
            [ai.brainyard.a2a.core.methods :as methods]
            [ai.brainyard.a2a.core.schema :as schema]))

;; =============================================================================
;; JSON-RPC (delegated to the shared codec)
;;
;; These are thin `defn` wrappers rather than `(def encode acp/encode)`
;; value-copies. An eager value-copy of a function can freeze as an unbound
;; fn under native-image — the failure `common/acp_agent.clj:70-80`
;; documents at length. Constants and schemas below are plain values and
;; are safe to copy.
;; =============================================================================

(defn make-id-source
  "Return a function yielding a fresh monotonically-increasing request id.
   Each A2A client owns its own."
  []
  (acp/make-id-source))

(defn request
  "Build a JSON-RPC request map."
  [id method params]
  (acp/request id method params))

(defn notification
  "Build a JSON-RPC notification map."
  [method params]
  (acp/notification method params))

(defn response
  "Build a JSON-RPC success response map."
  [id result]
  (acp/response id result))

(defn error-response
  "Build a JSON-RPC error response map."
  ([id code message]      (acp/error-response id code message))
  ([id code message data] (acp/error-response id code message data)))

(defn encode
  "Serialize a JSON-RPC message map to a JSON string."
  [msg]
  (acp/encode msg))

(defn decode
  "Parse a JSON string into a Clojure map with keyword keys. Throws on
   malformed JSON."
  [line]
  (acp/decode line))

(defn classify
  "Identify a parsed message as :request | :response | :notification | :invalid."
  [msg]
  (acp/classify msg))

(defn request?      [msg] (acp/request? msg))
(defn response?     [msg] (acp/response? msg))
(defn notification? [msg] (acp/notification? msg))
(defn error?        [msg] (acp/error? msg))

;; =============================================================================
;; Protocol constants
;; =============================================================================

(def ^{:doc "A2A protocol version we speak (Major.Minor). THE version literal."}
  PROTOCOL_VERSION methods/PROTOCOL_VERSION)
(def ^{:doc "HTTP header carrying the A2A-Version service parameter."}
  VERSION_HEADER methods/VERSION_HEADER)
(def ^{:doc "HTTP header carrying comma-separated A2A-Extensions URIs."}
  EXTENSIONS_HEADER methods/EXTENSIONS_HEADER)
(def ^{:doc "Well-known path serving the public Agent Card."}
  AGENT_CARD_PATH methods/AGENT_CARD_PATH)
(def ^{:doc "Content-Type for push notifications POSTed to a webhook."}
  PUSH_CONTENT_TYPE methods/PUSH_CONTENT_TYPE)

(def ^{:doc "JSON-RPC method names a client sends to a server, by keyword."}
  client-methods methods/client-methods)
(def ^{:doc "Method-name strings a server may implement."}
  server-methods methods/server-methods)
(def ^{:doc "TaskStatus.state wire values."} task-states methods/task-states)
(def ^{:doc "States after which no further work happens."} terminal-states methods/terminal-states)
(def ^{:doc "States that are PAUSED awaiting the client, not finished."}
  interrupted-states methods/interrupted-states)
(def ^{:doc "Message.role wire values."} roles methods/roles)
(def ^{:doc "Part.kind discriminator values."} part-kinds methods/part-kinds)
(def ^{:doc "Readonly `kind` discriminators on top-level result objects."}
  object-kinds methods/object-kinds)

(defn method-name
  "Resolve a method keyword (e.g. `:message-send`) to its wire string.
   Throws on an unknown keyword — a typo here would otherwise surface as
   `MethodNotFound` from every server in the ecosystem."
  [k]
  (or (get methods/client-methods k)
      (throw (ex-info (str "Unknown A2A method: " (pr-str k))
                      {:type :a2a/unknown-method :method k}))))

;; =============================================================================
;; Schemas
;; =============================================================================

(def ^{:doc "Malli schema for a Part (text | file | data)."} Part schema/Part)
(def ^{:doc "Malli schema for a vector of Parts."} Parts schema/Parts)
(def ^{:doc "Malli schema for the `file` payload of a file Part."} FileContent schema/FileContent)
(def ^{:doc "Malli schema for a Message."} Message schema/Message)
(def ^{:doc "Malli schema for an Artifact."} Artifact schema/Artifact)
(def ^{:doc "Malli schema for a TaskStatus."} TaskStatus schema/TaskStatus)
(def ^{:doc "Malli schema for a Task."} Task schema/Task)
(def ^{:doc "Malli schema for a TaskStatusUpdateEvent."} TaskStatusUpdateEvent schema/TaskStatusUpdateEvent)
(def ^{:doc "Malli schema for a TaskArtifactUpdateEvent."} TaskArtifactUpdateEvent schema/TaskArtifactUpdateEvent)
(def ^{:doc "Malli schema for one frame off a streaming response."} StreamResponse schema/StreamResponse)
(def ^{:doc "Malli schema for message/send params."} MessageSendParams schema/MessageSendParams)
(def ^{:doc "Malli schema for message/send configuration."} MessageSendConfiguration schema/MessageSendConfiguration)
(def ^{:doc "Malli schema for tasks/get params."} TaskQueryParams schema/TaskQueryParams)
(def ^{:doc "Malli schema for tasks/cancel and tasks/resubscribe params."} TaskIdParams schema/TaskIdParams)
(def ^{:doc "Malli schema for a PushNotificationConfig."} PushNotificationConfig schema/PushNotificationConfig)
(def ^{:doc "Malli schema for an Agent Card."} AgentCard schema/AgentCard)
(def ^{:doc "Malli schema for an AgentSkill."} AgentSkill schema/AgentSkill)
(def ^{:doc "Malli schema for AgentCapabilities."} AgentCapabilities schema/AgentCapabilities)
(def ^{:doc "Malli schema for an AgentInterface (transport binding)."} AgentInterface schema/AgentInterface)
(def ^{:doc "Malli schema for a SecurityScheme."} SecurityScheme schema/SecurityScheme)

(defn validate
  "Validate `value` against an A2A schema. Throws on failure."
  [schema value]
  (schema/validate schema value))

(defn valid?
  "Non-throwing schema validator."
  [schema value]
  (schema/valid? schema value))

(defn explain
  "Malli explanation, or nil when `value` validates."
  [schema value]
  (schema/explain schema value))

;; =============================================================================
;; Wire <-> keyword coercion + content helpers
;; =============================================================================

(defn state->kw
  "A2A wire state string -> keyword. Unknown/nil become `:unknown`."
  [state]
  (schema/state->kw state))

(defn kw->state
  "Keyword -> A2A wire state string. Accepts brainyard's two-L
   `:cancelled` as well as the protocol's one-L `:canceled`."
  [kw]
  (schema/kw->state kw))

(defn terminal?
  "True when a task state ends the task."
  [state]
  (schema/terminal? state))

(defn interrupted?
  "True when a task state means PAUSED-awaiting-client rather than
   finished. Treating these as terminal abandons a task the peer is still
   holding open for us."
  [state]
  (schema/interrupted? state))

(defn text-part  "Build a text Part."  [text] (schema/text-part text))
(defn data-part  "Build a data Part."  [data] (schema/data-part data))

(defn part-text
  "Text of a Part, or \"\" when it carries none."
  [part]
  (schema/part-text part))

(defn parts-text
  "Concatenated text of every text Part in `parts`."
  [parts]
  (schema/parts-text parts))

(defn message-text
  "Concatenated text content of a Message."
  [message]
  (schema/message-text message))

(defn artifact-id
  "An Artifact's id under either the v0.3 (`:artifactId`) or v1.0 proto
   (`:id`) name."
  [artifact]
  (schema/artifact-id artifact))

;; =============================================================================
;; Agent Card
;; =============================================================================

(defn base-url
  "Normalize a peer base URL (trim, drop trailing slash). nil when blank."
  [url]
  (card/base-url url))

(defn card-url
  "The well-known Agent Card URL for a peer base URL. Idempotent when
   handed the card URL itself."
  [url]
  (card/card-url url))

(defn parse-card
  "Validate a decoded Agent Card. Returns `{:card …}` or `{:error …}`.
   Never throws."
  [card]
  (card/parse card))

(defn build-card
  "Build an Agent Card for this agent. `:protocolVersion` is stamped
   automatically."
  [opts]
  (card/build opts))

(defn build-skill
  "Build an AgentSkill map."
  [opts]
  (card/skill opts))

(defn card-skills
  "Skills declared by a card."
  [card]
  (card/skills card))

(defn find-skill
  "Look up a skill by id, or nil."
  [card skill-id]
  (card/find-skill card skill-id))

(defn jsonrpc-endpoint
  "The JSON-RPC endpoint URL for a card, or nil when it offers none."
  [card]
  (card/jsonrpc-endpoint card))

(defn peer-agent-id
  "Identity of one remote skill for the cross-process call chain:
   `<endpoint-url>#<skill-id>`. URL-scoped on purpose — two peers can both
   expose a skill named `planner`."
  [card skill-id]
  (card/peer-agent-id card skill-id))

(defn card-supports?
  "True when the card advertises capability `k`. Absent means unsupported."
  [card k]
  (card/supports? card k))

(defn extended-card?
  "True when the card offers an authenticated extended card."
  [card]
  (card/extended-card? card))

(defn version-compatible?
  "True when a peer's protocol version can serve our requests. Compared by
   major version; absent/unparseable is treated as compatible."
  ([their-version] (card/compatible? their-version))
  ([their-version our-version] (card/compatible? their-version our-version)))

(defn card-version-error
  "Nil when a card's protocol version is compatible, else `{:error …}`."
  [card]
  (card/version-error card))

;; =============================================================================
;; Cross-process call chain (brainyard's A2A extension)
;;
;; Cycle/depth detection that survives a network hop. See
;; `ai.brainyard.a2a.core.chain` — including its trust-boundary note: this
;; is a cooperation protocol between well-behaved peers, NOT a security
;; control, since a remote caller controls the metadata it sends.
;; =============================================================================

(def ^{:doc "Message.metadata key carrying the call chain."} CHAIN_KEY chain/CHAIN_KEY)
(def ^{:doc "Message.metadata key carrying the call depth."} DEPTH_KEY chain/DEPTH_KEY)
(def ^{:doc "Message.metadata key carrying the caller's context id."} CONTEXT_KEY chain/CONTEXT_KEY)

(defn node-id
  "This process's stable A2A node identity — the token stamped on outbound
   calls and matched against inbound chains. It MUST be the same in both
   directions or the cycle guard cannot fire."
  []
  (chain/node-id))

(defn set-node-id!
  "Override the node id (tests; deterministic identity across restarts)."
  [id]
  (chain/set-node-id! id))

(defn stamp-chain
  "Metadata for an outbound request, appending THIS node to the chain.
   Opts: `{:chain :depth :context-id}` (`:self-id` overrides, for tests)."
  [opts]
  (chain/stamp opts))

(defn read-chain
  "Call chain from inbound metadata, as strings. `[]` when absent."
  [metadata]
  (chain/read-chain metadata))

(defn read-depth
  "Call depth from inbound metadata. 0 when absent or unparseable."
  [metadata]
  (chain/read-depth metadata))

(defn read-context-id
  "Caller's context id from inbound metadata, or nil."
  [metadata]
  (chain/read-context-id metadata))

(defn chain-cycle?
  "True when `self` (default: this node) already appears in `chain`."
  ([chain-v] (chain/cycle? chain-v))
  ([chain-v self] (chain/cycle? chain-v self)))

(defn check-chain
  "Gate an inbound request. nil to proceed, else `{:error … :reason :cycle|:depth}`.
   Call BEFORE doing any work — refusing after spending an LLM turn defeats
   the purpose. Opts: `{:metadata :self-id :max-depth}`."
  [opts]
  (chain/check opts))

(defn inbound-chain
  "The chain to bind locally while servicing an inbound request. Does NOT
   append this node — the next outbound `stamp-chain` does that."
  [metadata]
  (chain/inbound-chain metadata))

(defn describe-chain
  "Human-readable one-liner for logs and errors."
  [metadata]
  (chain/describe metadata))

;; =============================================================================
;; Errors
;; =============================================================================

(def ^{:doc "Standard JSON-RPC 2.0 error codes by keyword."} standard-codes errors/standard-codes)
(def ^{:doc "A2A-specific error codes by keyword."} a2a-codes errors/a2a-codes)
(def ^{:doc "All error codes by keyword."} error-codes errors/all-codes)

(defn error-code
  "Numeric code for an error keyword."
  [k]
  (errors/code-of k))

(defn error-message
  "Canonical message for an error keyword."
  [k]
  (errors/message-of k))

(defn error->jsonrpc
  "Build a JSON-RPC error response from an error keyword."
  ([id k]      (errors/->jsonrpc id k))
  ([id k data] (errors/->jsonrpc id k data)))

(defn error-not-found
  "The single constructor for both \"no such task\" and \"not yours\" —
   the responses must be indistinguishable so the error channel is not an
   enumeration oracle. Takes no resource identity, deliberately."
  [id]
  (errors/not-found id))

(defn error-unsupported
  "The agent does not implement this operation."
  ([id]        (errors/unsupported id))
  ([id detail] (errors/unsupported id detail)))

(defn error-invalid-params
  ([id]        (errors/invalid-params id))
  ([id detail] (errors/invalid-params id detail)))

(defn error-internal
  ([id]        (errors/internal id))
  ([id detail] (errors/internal id detail)))

(defn error-key
  "Keyword for a JSON-RPC error object's code, or `:unknown-error`."
  [err]
  (errors/error-key err))

(defn error->result
  "Convert a JSON-RPC error object into brainyard's `{:error …}` shape,
   carrying `:error-key` so callers branch on kind rather than prose."
  [err]
  (errors/->result err))

(defn error-retryable?
  "True when retrying the identical request could plausibly succeed.
   Deliberately narrow — only internal failures qualify."
  [err]
  (errors/retryable? err))
