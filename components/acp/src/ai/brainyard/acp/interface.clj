;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.acp.interface
  "Public API for the ACP (Agent Client Protocol) component.

   Three layers, intentionally narrow:

   - **JSON-RPC 2.0** (request/response/notification construction,
     encoding, decoding, classification, id allocation).
   - **Transport** (`ITransport` protocol) and a stdio implementation
     that spawns a subprocess and exchanges line-delimited JSON.
   - **Schemas** for ACP-specific payloads (content blocks, plans,
     tool calls, session/update variants).

   This component is **pure protocol plumbing**. It must not depend on
   `agent` or `clj-llm`; lifecycle, dispatch, and event-bridge logic
   live in components/acp-client. See docs/acp-design.md."
  (:require [ai.brainyard.acp.core.jsonrpc :as jsonrpc]
            [ai.brainyard.acp.core.methods :as methods]
            [ai.brainyard.acp.core.schema :as schema]
            [ai.brainyard.acp.core.transport :as transport]
            [ai.brainyard.acp.core.transport.stdio :as stdio]))

;; =============================================================================
;; JSON-RPC
;; =============================================================================

(defn make-id-source
  "Return a function that yields a fresh monotonically-increasing
   request id on each call. Each ACP client owns its own."
  []
  (jsonrpc/make-id-source))

(defn request
  "Build a JSON-RPC request map."
  [id method params]
  (jsonrpc/request id method params))

(defn notification
  "Build a JSON-RPC notification map."
  [method params]
  (jsonrpc/notification method params))

(defn response
  "Build a JSON-RPC success response map."
  [id result]
  (jsonrpc/response id result))

(defn error-response
  "Build a JSON-RPC error response map."
  ([id code message]      (jsonrpc/error-response id code message))
  ([id code message data] (jsonrpc/error-response id code message data)))

(defn encode
  "Serialize a JSON-RPC message map to a JSON string."
  [msg]
  (jsonrpc/encode msg))

(defn decode
  "Parse a JSON-RPC line into a Clojure map. Throws on malformed JSON."
  [line]
  (jsonrpc/decode line))

(defn classify
  "Identify a parsed message as :request | :response | :notification | :invalid."
  [msg]
  (jsonrpc/classify msg))

(defn request?      [msg] (jsonrpc/request? msg))
(defn response?     [msg] (jsonrpc/response? msg))
(defn notification? [msg] (jsonrpc/notification? msg))
(defn error?        [msg] (jsonrpc/error? msg))

(def ^{:doc "JSON-RPC version constant."} JSON_RPC_VERSION jsonrpc/JSON_RPC_VERSION)
(def ^{:doc "Standard JSON-RPC error codes by keyword name."} error-codes jsonrpc/error-codes)

;; =============================================================================
;; ACP method names + protocol version
;; =============================================================================

(def ^{:doc "ACP protocol version we implement."} PROTOCOL_VERSION methods/PROTOCOL_VERSION)
(def ^{:doc "Methods the client sends to the agent."} client-methods methods/client-methods)
(def ^{:doc "Methods the agent sends to the client."} agent-methods methods/agent-methods)
(def ^{:doc "Valid stop reasons returned by session/prompt."} stop-reasons methods/stop-reasons)
(def ^{:doc "Valid tool-call kinds."} tool-call-kinds methods/tool-call-kinds)
(def ^{:doc "Valid tool-call statuses."} tool-call-statuses methods/tool-call-statuses)

;; =============================================================================
;; Schemas
;; =============================================================================

(def ^{:doc "Malli schema for a session/update payload."} SessionUpdate schema/SessionUpdate)
(def ^{:doc "Malli schema for an ACP content block."} ContentBlock schema/ContentBlock)
(def ^{:doc "Malli schema for a tool-call object."} ToolCall schema/ToolCall)
(def ^{:doc "Malli schema for a plan (vector of plan entries)."} Plan schema/Plan)
(def ^{:doc "Malli schema for session/prompt params."} SessionPromptParams schema/SessionPromptParams)
(def ^{:doc "Malli schema for session/prompt result."} SessionPromptResult schema/SessionPromptResult)
(def ^{:doc "Malli schema for session/new params."} SessionNewParams schema/SessionNewParams)
(def ^{:doc "Malli schema for session/new result."} SessionNewResult schema/SessionNewResult)
(def ^{:doc "Malli schema for session/cancel params."} SessionCancelParams schema/SessionCancelParams)
(def ^{:doc "Malli schema for session/request_permission params."}
  SessionRequestPermissionParams schema/SessionRequestPermissionParams)
(def ^{:doc "Malli schema for session/request_permission result."}
  SessionRequestPermissionResult schema/SessionRequestPermissionResult)
(def ^{:doc "Malli schema for initialize params."} InitializeParams schema/InitializeParams)
(def ^{:doc "Malli schema for initialize result."} InitializeResult schema/InitializeResult)

(defn validate
  "Validate `value` against an ACP schema. Throws on failure."
  [schema value]
  (schema/validate schema value))

(defn valid?
  "Non-throwing schema validator."
  [schema value]
  (schema/valid? schema value))

;; =============================================================================
;; Transport
;; =============================================================================

(defn create-stdio-transport
  "Create a (not yet opened) stdio ACP transport.

   Options map:
     :command     — vector<string>, required (e.g. [\"node\" \"agent.js\"])
     :working-dir — cwd for the spawned process (string)
     :env         — map of additional env vars"
  [opts]
  (stdio/create opts))

(defn open!
  "Open a transport (spawn subprocess, start I/O threads). Returns the transport."
  [t]
  (transport/open! t))

(defn read-message!
  "Block until a parsed JSON-RPC message arrives. With timeout-ms,
   returns nil if no message arrives in the window."
  ([t]            (transport/read-message! t))
  ([t timeout-ms] (transport/read-message! t timeout-ms)))

(defn write-message!
  "Encode `msg` as JSON, append a newline, write to the peer."
  [t msg]
  (transport/write-message! t msg))

(defn open?
  "True while the transport is usable."
  [t]
  (transport/open? t))

(defn close!
  "Release transport resources. Idempotent."
  [t]
  (transport/close! t))
