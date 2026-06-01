;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.acp.core.schema
  "Malli schemas for ACP protocol message bodies.

   These schemas validate at the boundary between the wire and the rest
   of the system — payloads of `session/update`, `session/prompt`,
   `initialize`, and so on. Use them in transport tests and in the
   future acp-client event bridge to fail fast on malformed payloads.

   The transport itself (jsonrpc + stdio) deals in plain JSON-RPC
   envelopes; these schemas only describe the contents of `params` and
   `result` fields — i.e. ACP-specific structure."
  (:require [malli.core :as m]
            [ai.brainyard.acp.core.methods :as methods]))

;; =============================================================================
;; Primitive enums
;; =============================================================================

(def ToolCallKind
  (into [:enum] methods/tool-call-kinds))

(def ToolCallStatus
  (into [:enum] methods/tool-call-statuses))

(def StopReason
  (into [:enum] methods/stop-reasons))

;; =============================================================================
;; Content blocks (text, image, audio, resource, resource_link)
;; =============================================================================

(def ContentBlock
  "Polymorphic ACP content block, dispatched on `:type`."
  [:multi {:dispatch :type}
   ["text"          [:map {:closed false}
                     [:type [:= "text"]]
                     [:text :string]]]
   ["image"         [:map {:closed false}
                     [:type [:= "image"]]
                     [:mimeType {:optional true} :string]
                     [:data {:optional true} :string]
                     [:uri {:optional true} :string]]]
   ["audio"         [:map {:closed false}
                     [:type [:= "audio"]]
                     [:mimeType {:optional true} :string]
                     [:data {:optional true} :string]
                     [:uri {:optional true} :string]]]
   ["resource"      [:map {:closed false}
                     [:type [:= "resource"]]
                     [:resource :map]]]
   ["resource_link" [:map {:closed false}
                     [:type [:= "resource_link"]]
                     [:uri :string]
                     [:name {:optional true} :string]
                     [:mimeType {:optional true} :string]]]])

;; =============================================================================
;; Plan entries (the agent's todo list)
;; =============================================================================

(def PlanEntry
  [:map {:closed false}
   [:content :string]
   [:priority {:optional true} :string]
   [:status {:optional true} [:enum "pending" "in_progress" "completed"]]])

(def Plan
  [:vector PlanEntry])

;; =============================================================================
;; Tool calls (lifecycle + progressively-streamed result)
;; =============================================================================

(def ToolCall
  [:map {:closed false}
   [:toolCallId :string]
   [:title {:optional true} :string]
   [:kind {:optional true} ToolCallKind]
   [:status {:optional true} ToolCallStatus]
   [:rawInput {:optional true} [:maybe :map]]
   [:content {:optional true} [:vector ContentBlock]]
   [:locations {:optional true} [:vector :map]]])

;; =============================================================================
;; session/update — the streaming workhorse (discriminated union)
;; =============================================================================

(def SessionUpdate
  "Payload of a `session/update` notification. Discriminated on
   `:sessionUpdate` (the kind of update). Each variant carries different
   keys; we pin the shape of the common ones and leave the rest open."
  [:and
   [:map {:closed false}
    [:sessionId :string]
    [:sessionUpdate :string]]
   [:multi {:dispatch :sessionUpdate}
    ["agent_message_chunk"  [:map {:closed false}
                             [:sessionUpdate [:= "agent_message_chunk"]]
                             [:content ContentBlock]]]
    ["agent_thought_chunk"  [:map {:closed false}
                             [:sessionUpdate [:= "agent_thought_chunk"]]
                             [:content ContentBlock]]]
    ["plan"                 [:map {:closed false}
                             [:sessionUpdate [:= "plan"]]
                             [:entries Plan]]]
    ["tool_call"            [:map {:closed false}
                             [:sessionUpdate [:= "tool_call"]]
                             [:toolCall ToolCall]]]
    ["tool_call_update"     [:map {:closed false}
                             [:sessionUpdate [:= "tool_call_update"]]
                             [:toolCall ToolCall]]]
    [::m/default            [:map {:closed false}
                             [:sessionUpdate :string]]]]])

;; =============================================================================
;; session/prompt + session/new + session/cancel + session/request_permission
;; =============================================================================

(def SessionPromptParams
  [:map {:closed false}
   [:sessionId :string]
   [:prompt [:vector ContentBlock]]])

(def SessionPromptResult
  [:map {:closed false}
   [:stopReason StopReason]])

(def SessionNewParams
  [:map {:closed false}
   [:cwd {:optional true} :string]
   [:mcpServers {:optional true} [:vector :map]]])

(def SessionNewResult
  [:map {:closed false}
   [:sessionId :string]])

(def SessionCancelParams
  [:map {:closed false}
   [:sessionId :string]])

(def PermissionOption
  [:map {:closed false}
   [:optionId :string]
   [:name :string]
   [:kind {:optional true} :string]])

(def SessionRequestPermissionParams
  [:map {:closed false}
   [:sessionId :string]
   [:toolCall ToolCall]
   [:options [:vector PermissionOption]]])

(def SessionRequestPermissionResult
  [:map {:closed false}
   [:outcome [:map {:closed false}
              [:outcome :string]
              [:optionId {:optional true} :string]]]])

;; =============================================================================
;; initialize
;; =============================================================================

(def Implementation
  [:map {:closed false}
   [:name :string]
   [:version {:optional true} :string]])

(def InitializeParams
  [:map {:closed false}
   [:protocolVersion :string]
   [:clientCapabilities {:optional true} :map]
   [:clientInfo {:optional true} Implementation]])

(def InitializeResult
  [:map {:closed false}
   [:protocolVersion :string]
   [:agentCapabilities {:optional true} :map]
   [:authMethods {:optional true} [:vector :map]]])

;; =============================================================================
;; Validation helpers
;; =============================================================================

(defn validate
  "Validate `value` against `schema`. Returns true on success, throws
   ExceptionInfo with the explanation on failure."
  [schema value]
  (if (m/validate schema value)
    true
    (throw (ex-info "ACP schema validation failed"
                    {:type :acp/schema-error
                     :schema schema
                     :value value
                     :explain (m/explain schema value)}))))

(defn valid?
  "Non-throwing variant of `validate`."
  [schema value]
  (m/validate schema value))
