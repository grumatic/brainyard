;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a.core.schema
  "Malli schemas for A2A protocol payloads, plus wire<->keyword coercion.

   These validate at the boundary between the wire and the rest of the
   system — the `params` and `result` bodies of the JSON-RPC methods in
   `core/methods.clj`. The JSON-RPC envelope itself is somebody else's
   problem (`ai.brainyard.acp.interface`).

   ## Everything is open, on purpose

   Every map here is `{:closed false}`. A2A has a first-class extension
   mechanism (`AgentCard.extensions`, `Message.extensions`, the
   `A2A-Extensions` header) whose entire premise is that peers add fields
   we have never heard of. A closed schema would reject a conformant peer
   for being newer than us. We validate the fields we rely on and let the
   rest through untouched — including back out again, so we never silently
   drop an extension we were merely passing along.

   ## Which spelling these describe

   The **JSON-RPC binding**, v0.3-style: the discriminator is `kind` (not
   `type`, which is ACP's spelling), ids are `messageId` / `contextId` /
   `taskId` / `artifactId`, and task states are lowercase-kebab strings.
   The protobuf enum names (`TASK_STATE_INPUT_REQUIRED`) that also appear
   in A2A documentation describe the same protocol one layer down and do
   not travel on this wire. See `core/methods.clj`.

   Keys arrive keywordized (`acp/decode` uses `:key-fn keyword`), so the
   schemas are written against keyword keys but JSON-shaped names —
   `:messageId`, not `:message-id`."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [ai.brainyard.a2a.core.methods :as methods]))

;; =============================================================================
;; Primitive enums
;; =============================================================================

(def TaskState
  (into [:enum] (sort methods/task-states)))

(def Role
  (into [:enum] (sort methods/roles)))

(def PartKind
  (into [:enum] (sort methods/part-kinds)))

;; =============================================================================
;; Part — the atom of content
;;
;; Discriminated on `:kind`. The ::m/default arm matters: a peer speaking a
;; newer minor version can introduce a part kind we do not know, and the
;; correct response is to carry it rather than reject the whole message.
;; =============================================================================

(def FileContent
  "The `file` payload of a file Part. Exactly one of `:bytes` (base64) or
   `:uri` is expected in practice, but the spec does not forbid both, so
   neither is required here."
  [:map {:closed false}
   [:name {:optional true} :string]
   [:mimeType {:optional true} :string]
   [:bytes {:optional true} :string]
   [:uri {:optional true} :string]])

(def Part
  [:multi {:dispatch :kind}
   ["text" [:map {:closed false}
            [:kind [:= "text"]]
            [:text :string]
            [:metadata {:optional true} [:maybe :map]]]]
   ["file" [:map {:closed false}
            [:kind [:= "file"]]
            [:file FileContent]
            [:metadata {:optional true} [:maybe :map]]]]
   ["data" [:map {:closed false}
            [:kind [:= "data"]]
            [:data :map]
            [:metadata {:optional true} [:maybe :map]]]]
   [::m/default [:map {:closed false}
                 [:kind :string]]]])

(def Parts
  [:vector Part])

;; =============================================================================
;; Message
;; =============================================================================

(def Message
  [:map {:closed false}
   [:messageId :string]
   [:role Role]
   [:parts Parts]
   [:kind {:optional true} [:= "message"]]
   [:taskId {:optional true} [:maybe :string]]
   [:contextId {:optional true} [:maybe :string]]
   [:metadata {:optional true} [:maybe :map]]
   [:extensions {:optional true} [:vector :string]]
   [:referenceTaskIds {:optional true} [:vector :string]]])

;; =============================================================================
;; Artifact + Task
;; =============================================================================

(def Artifact
  "A task output. `:artifactId` is the v0.3 JSON name; the v1.0 protobuf
   calls the same field `id`, so both are optional and `artifact-id`
   reads whichever is present."
  [:map {:closed false}
   [:artifactId {:optional true} :string]
   [:id {:optional true} :string]
   [:name {:optional true} :string]
   [:description {:optional true} :string]
   [:parts Parts]
   [:metadata {:optional true} [:maybe :map]]
   [:extensions {:optional true} [:vector :string]]])

(def TaskStatus
  [:map {:closed false}
   [:state TaskState]
   [:message {:optional true} [:maybe Message]]
   [:timestamp {:optional true} :string]])

(def Task
  [:map {:closed false}
   [:id :string]
   [:contextId {:optional true} [:maybe :string]]
   [:status TaskStatus]
   [:kind {:optional true} [:= "task"]]
   [:history {:optional true} [:vector Message]]
   [:artifacts {:optional true} [:vector Artifact]]
   [:metadata {:optional true} [:maybe :map]]])

;; =============================================================================
;; Streaming events
;; =============================================================================

(def TaskStatusUpdateEvent
  [:map {:closed false}
   [:taskId :string]
   [:contextId {:optional true} [:maybe :string]]
   [:kind {:optional true} [:= "status-update"]]
   [:status TaskStatus]
   [:final {:optional true} :boolean]
   [:metadata {:optional true} [:maybe :map]]])

(def TaskArtifactUpdateEvent
  [:map {:closed false}
   [:taskId :string]
   [:contextId {:optional true} [:maybe :string]]
   [:kind {:optional true} [:= "artifact-update"]]
   [:artifact Artifact]
   [:append {:optional true} :boolean]
   [:lastChunk {:optional true} :boolean]
   [:metadata {:optional true} [:maybe :map]]])

(def StreamResponse
  "One frame off a `message/stream` or `tasks/resubscribe` SSE stream.
   Discriminated on `:kind`; a frame with no `:kind` at all is tolerated
   and left to the caller (some servers omit it on the initial Task)."
  [:multi {:dispatch :kind}
   ["message"         Message]
   ["task"            Task]
   ["status-update"   TaskStatusUpdateEvent]
   ["artifact-update" TaskArtifactUpdateEvent]
   [::m/default       [:map {:closed false}]]])

;; =============================================================================
;; Method params / results
;; =============================================================================

(def MessageSendConfiguration
  [:map {:closed false}
   [:acceptedOutputModes {:optional true} [:vector :string]]
   [:historyLength {:optional true} [:maybe :int]]
   [:blocking {:optional true} :boolean]
   [:pushNotificationConfig {:optional true} [:maybe :map]]])

(def MessageSendParams
  [:map {:closed false}
   [:message Message]
   [:configuration {:optional true} [:maybe MessageSendConfiguration]]
   [:metadata {:optional true} [:maybe :map]]])

(def TaskQueryParams
  "Params of `tasks/get`. `:historyLength` 0 means \"omit history\";
   unset means \"server default\" — they are not the same request."
  [:map {:closed false}
   [:id :string]
   [:historyLength {:optional true} [:maybe :int]]
   [:metadata {:optional true} [:maybe :map]]])

(def TaskIdParams
  "Params of `tasks/cancel` and `tasks/resubscribe`."
  [:map {:closed false}
   [:id :string]
   [:metadata {:optional true} [:maybe :map]]])

(def PushNotificationAuthenticationInfo
  [:map {:closed false}
   [:schemes {:optional true} [:vector :string]]
   [:credentials {:optional true} [:maybe :string]]])

(def PushNotificationConfig
  [:map {:closed false}
   [:id {:optional true} :string]
   [:url :string]
   [:token {:optional true} [:maybe :string]]
   [:authentication {:optional true} [:maybe PushNotificationAuthenticationInfo]]])

;; =============================================================================
;; Agent Card
;; =============================================================================

(def AgentCapabilities
  [:map {:closed false}
   [:streaming {:optional true} :boolean]
   [:pushNotifications {:optional true} :boolean]
   [:stateTransitionHistory {:optional true} :boolean]
   [:extensions {:optional true} [:vector :map]]])

(def AgentSkill
  "One invocable capability. This is what a client actually calls, and what
   a brainyard `defagent` maps onto (see docs/design/a2a-design.md §5.6)."
  [:map {:closed false}
   [:id :string]
   [:name :string]
   [:description {:optional true} :string]
   [:tags {:optional true} [:vector :string]]
   [:examples {:optional true} [:vector :string]]
   [:inputModes {:optional true} [:vector :string]]
   [:outputModes {:optional true} [:vector :string]]])

(def AgentProvider
  [:map {:closed false}
   [:organization :string]
   [:url {:optional true} :string]])

(def AgentInterface
  "One transport binding the agent is reachable on. A card may advertise
   several (JSON-RPC, gRPC, REST); we consume the JSON-RPC one.

   The binding field is named differently by card generation —
   `:transport` under v0.3's `:additionalInterfaces`, `:protocolBinding`
   under v1.0's `:supportedInterfaces` — so BOTH are optional here and
   `card/jsonrpc-endpoint` reads whichever is present. Requiring either one
   would reject a conformant card from the other generation."
  [:map {:closed false}
   [:url :string]
   [:transport {:optional true} :string]
   [:protocolBinding {:optional true} :string]
   [:protocolVersion {:optional true} :string]])

(def SecurityScheme
  [:map {:closed false}
   [:type [:enum "apiKey" "http" "oauth2" "openIdConnect" "mutualTLS"]]
   [:description {:optional true} :string]
   [:scheme {:optional true} :string]
   [:in {:optional true} :string]
   [:name {:optional true} :string]])

(def AgentCard
  [:map {:closed false}
   [:name :string]
   [:description {:optional true} :string]
   [:url {:optional true} :string]
   [:version {:optional true} :string]
   [:protocolVersion {:optional true} :string]
   [:preferredTransport {:optional true} :string]
   [:provider {:optional true} [:maybe AgentProvider]]
   [:capabilities {:optional true} [:maybe AgentCapabilities]]
   [:skills {:optional true} [:vector AgentSkill]]
   ;; v0.3 spelling and v1.0 spelling of the same idea. Neither is required:
   ;; a v1.0 card carries only `:supportedInterfaces` (and no top-level
   ;; `:url`), a v0.3 card only `:additionalInterfaces`.
   [:additionalInterfaces {:optional true} [:vector AgentInterface]]
   [:supportedInterfaces {:optional true} [:vector AgentInterface]]
   [:securitySchemes {:optional true} [:maybe :map]]
   [:security {:optional true} [:vector :map]]
   [:defaultInputModes {:optional true} [:vector :string]]
   [:defaultOutputModes {:optional true} [:vector :string]]
   [:supportsAuthenticatedExtendedCard {:optional true} :boolean]
   [:signatures {:optional true} [:vector :map]]])

;; =============================================================================
;; Wire <-> keyword coercion
;;
;; Kept here rather than at call sites so the `canceled`/`cancelled`
;; mismatch is spelled out exactly once.
;; =============================================================================

(def ^:private kw->wire-state
  "Keyword task state -> A2A wire string. Both spellings of cancelled map
   to the protocol's one-L `\"canceled\"`, so a caller who reaches for
   brainyard's native `:cancelled` gets a conformant request instead of a
   silently unknown state."
  {:submitted      "submitted"
   :working        "working"
   :input-required "input-required"
   :completed      "completed"
   :canceled       "canceled"
   :cancelled      "canceled"
   :failed         "failed"
   :rejected       "rejected"
   :auth-required  "auth-required"
   :unknown        "unknown"})

(defn state->kw
  "A2A wire state string -> keyword. Unknown or nil states become
   `:unknown` rather than throwing — a peer on a newer minor version may
   legitimately send a state we have not heard of, and treating that as a
   crash would be worse than treating it as opaque."
  [state]
  (let [s (some-> state str str/trim str/lower-case)]
    (if (contains? methods/task-states s)
      (keyword s)
      :unknown)))

(defn kw->state
  "Keyword -> A2A wire state string. Accepts brainyard's `:cancelled`
   (two Ls) as well as the protocol's `:canceled`. Unknown keywords yield
   `\"unknown\"`."
  [kw]
  (get kw->wire-state kw "unknown"))

(defn terminal?
  "True when `state` (wire string or keyword) ends the task."
  [state]
  (contains? methods/terminal-states
             (if (keyword? state) (kw->state state) (str state))))

(defn interrupted?
  "True when `state` means PAUSED-awaiting-client (`input-required` /
   `auth-required`) rather than finished. Callers that lump this in with
   `terminal?` abandon a task the peer is still holding open."
  [state]
  (contains? methods/interrupted-states
             (if (keyword? state) (kw->state state) (str state))))

;; =============================================================================
;; Accessors that paper over version drift
;; =============================================================================

(defn artifact-id
  "Read an Artifact's id under either the v0.3 (`:artifactId`) or v1.0
   proto (`:id`) name."
  [artifact]
  (or (:artifactId artifact) (:id artifact)))

(defn text-part
  "Build a text Part."
  [text]
  {:kind "text" :text (str text)})

(defn data-part
  "Build a data Part carrying an arbitrary JSON-encodable map."
  [data]
  {:kind "data" :data data})

(defn part-text
  "Extract text from a Part, or \"\" when it carries none. Non-text parts
   are not stringified — a file or data part has no text and pretending
   otherwise produces junk in a transcript."
  [part]
  (if (= "text" (:kind part)) (or (:text part) "") ""))

(defn parts-text
  "Concatenate the text of every text Part in `parts`."
  [parts]
  (->> parts (map part-text) (str/join)))

(defn message-text
  "Concatenate the text content of a Message."
  [message]
  (parts-text (:parts message)))

;; =============================================================================
;; Validation helpers (same shape as ai.brainyard.acp.core.schema)
;; =============================================================================

(defn validate
  "Validate `value` against `schema`. Returns true on success, throws
   ExceptionInfo carrying the explanation on failure."
  [schema value]
  (if (m/validate schema value)
    true
    (throw (ex-info "A2A schema validation failed"
                    {:type    :a2a/schema-error
                     :schema  schema
                     :value   value
                     :explain (m/explain schema value)}))))

(defn valid?
  "Non-throwing variant of `validate`."
  [schema value]
  (m/validate schema value))

(defn explain
  "Malli explanation for `value` against `schema`, or nil when it validates."
  [schema value]
  (m/explain schema value))
