;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a.core.methods
  "A2A method names, protocol constants, and wire enumerations.

   Sourced from <https://a2a-protocol.org/v0.3.0/specification/> (the
   JSON-RPC 2.0 binding). No logic — only constants, so callers reference
   the protocol symbolically and the literals live in exactly one place.

   ## Two spellings of the same protocol

   A2A's normative artifact is a protobuf schema (`spec/a2a.proto`), and
   its enum constants are spelled `TASK_STATE_INPUT_REQUIRED`. The
   **JSON-RPC binding does not use those names** — on the wire a task
   state is the lowercase-kebab string `\"input-required\"`. Both spellings
   appear in the published documentation, describing the same thing at
   different layers. Everything in this namespace is the **JSON wire
   form**, because that is what a real server sends us.

   ## The one-L trap

   A2A spells the cancelled state `\"canceled\"` (US, one L). Brainyard's
   task manager spells its own status `:cancelled` (two Ls,
   `agent/task/protocol.clj`). These are different tokens for the same
   idea and the conversion lives in `core/schema.clj`
   (`state->kw` / `kw->state`) — do not hand-roll it at call sites, and do
   not \"fix\" the wire spelling.")

;; =============================================================================
;; Protocol constants
;; =============================================================================

(def ^:const PROTOCOL_VERSION
  "The A2A protocol version this implementation speaks, as Major.Minor.

   Sent as the `A2A-Version` service parameter on every request; a server
   that cannot serve it replies `VersionNotSupportedError`. Patch versions
   deliberately never appear here — the spec forbids them in requests and
   Agent Cards, and bindings negotiate on Major.Minor alone.

   This is THE version literal for the whole codebase. A2A ships on a
   public RFC cadence; a second copy of this string somewhere else is how
   a project ends up shipping two protocol versions at once."
  "0.3")

(def ^:const VERSION_HEADER
  "HTTP header carrying the `A2A-Version` service parameter."
  "A2A-Version")

(def ^:const EXTENSIONS_HEADER
  "HTTP header carrying the comma-separated `A2A-Extensions` URIs."
  "A2A-Extensions")

(def ^:const AGENT_CARD_PATH
  "Well-known path (RFC 8615) serving the public Agent Card, unauthenticated."
  "/.well-known/agent-card.json")

(def ^:const PUSH_CONTENT_TYPE
  "Content-Type a server uses when POSTing a push notification to a webhook."
  "application/a2a+json")

;; =============================================================================
;; JSON-RPC method names
;;
;; Literal `\"method\"` field values in the JSON-RPC 2.0 binding. Verified
;; against the v0.3.0 specification. Getting one of these wrong does not
;; fail loudly — it produces `MethodNotFound` against every conformant
;; server in the ecosystem — so they are pinned here and referenced
;; symbolically everywhere else.
;; =============================================================================

(def client-methods
  "Methods a client (us, when calling out) sends to an A2A server."
  {:message-send        "message/send"
   :message-stream      "message/stream"
   :tasks-get           "tasks/get"
   :tasks-list          "tasks/list"
   :tasks-cancel        "tasks/cancel"
   :tasks-resubscribe   "tasks/resubscribe"
   :push-config-set     "tasks/pushNotificationConfig/set"
   :push-config-get     "tasks/pushNotificationConfig/get"
   :push-config-list    "tasks/pushNotificationConfig/list"
   :push-config-delete  "tasks/pushNotificationConfig/delete"
   :agent-extended-card "agent/getAuthenticatedExtendedCard"})

(def server-methods
  "The subset a server (us, when serving) must implement to be useful.

   `tasks/list` and the push-notification family are optional and gated by
   the Agent Card's `capabilities`; a server that omits them answers
   `UnsupportedOperationError` rather than `MethodNotFound`."
  #{"message/send" "message/stream"
    "tasks/get" "tasks/list" "tasks/cancel" "tasks/resubscribe"
    "tasks/pushNotificationConfig/set" "tasks/pushNotificationConfig/get"
    "tasks/pushNotificationConfig/list" "tasks/pushNotificationConfig/delete"
    "agent/getAuthenticatedExtendedCard"})

;; =============================================================================
;; Wire enumerations
;; =============================================================================

(def task-states
  "`TaskStatus.state` values, JSON wire form.

   Note `\"canceled\"` (one L) and the two kebab-cased interrupted states."
  #{"submitted" "working" "input-required" "completed"
    "canceled" "failed" "rejected" "auth-required" "unknown"})

(def terminal-states
  "States after which no further work happens and streams close."
  #{"completed" "canceled" "failed" "rejected"})

(def interrupted-states
  "States where the task is PAUSED awaiting the client, not finished.

   The easy and expensive mistake is treating these as terminal: the
   server is still holding the task open for us, and mapping either to a
   local failure abandons work we could have resumed."
  #{"input-required" "auth-required"})

(def roles
  "`Message.role` values, JSON wire form."
  #{"user" "agent"})

(def part-kinds
  "`Part.kind` discriminator values. Note the field is `kind`, not `type`
   — `type` is the ACP spelling, and the two protocols differ here."
  #{"text" "file" "data"})

(def object-kinds
  "Readonly `kind` discriminators on top-level result objects, used to tell
   a `Message` result from a `Task` result on the same method."
  {:message         "message"
   :task            "task"
   :status-update   "status-update"
   :artifact-update "artifact-update"})

(def security-schemes
  "`AgentCard.securitySchemes` type values."
  #{"apiKey" "http" "oauth2" "openIdConnect" "mutualTLS"})
