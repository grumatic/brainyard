;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.acp.core.methods
  "ACP method and notification name constants.

   Sourced from <https://agentclientprotocol.com/protocol/overview> and the
   JSON-RPC method docs. No logic — only string constants so callers can
   reference methods symbolically.")

(def ^:const PROTOCOL_VERSION
  "ACP protocol version this client implements. Sent in `initialize`.
   The ACP spec versions the protocol as an integer (major version),
   not a semver string — real agents (claude-code-acp, gemini, codex)
   reject a string `protocolVersion` with `Invalid params`."
  1)

(def client-methods
  "Methods the client (us) sends to the agent."
  {:initialize     "initialize"
   :authenticate   "authenticate"
   :session-new    "session/new"
   :session-load   "session/load"
   :session-prompt "session/prompt"
   :session-cancel "session/cancel"})

(def agent-methods
  "Methods the agent sends back to the client (reverse calls + notifications)."
  {:session-update             "session/update"
   :session-request-permission "session/request_permission"
   :fs-read-text-file          "fs/read_text_file"
   :fs-write-text-file         "fs/write_text_file"})

(def stop-reasons
  "Valid `StopReason` values returned from `session/prompt`."
  #{"end_turn" "max_tokens" "max_turn_requests" "refusal" "cancelled"})

(def tool-call-kinds
  "ACP tool-call `kind` enumeration."
  #{"read" "edit" "delete" "move" "search" "execute" "think" "fetch" "other"})

(def tool-call-statuses
  "ACP tool-call lifecycle statuses."
  #{"pending" "in_progress" "completed" "failed"})

(def permission-option-ids
  "Canonical permission option ids (agents may supply others)."
  #{"allow_once" "allow_always" "reject_once" "reject_always"})
