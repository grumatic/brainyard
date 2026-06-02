;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.acp-client.core.session
  "ACP session lifecycle on top of the dispatcher.

   A session is a logical conversation with an ACP agent inside an
   AcpClient. Phase 5 wires this up to the agent runtime; for now it's
   used directly by tests and (eventually) by a `:acp` clj-llm
   provider."
  (:require [ai.brainyard.acp-client.core.client :as client]
            [ai.brainyard.acp-client.core.events :as events]))

;; =============================================================================
;; AcpSession — a thin handle, not its own record (state lives in the
;; remote agent + the client's pending-requests + caller-provided
;; on-event closure).
;; =============================================================================

(defn new!
  "Create a new ACP session via `session/new`.

   Opts:
     :cwd          — workspace cwd advertised to the agent (string).
     :mcp-servers  — vector of MCP server configs (default []).
     :timeout-ms   — handshake timeout (default 30000).

   Returns: {:session-id str :client AcpClient}"
  ([client] (new! client {}))
  ([acp-client {:keys [cwd mcp-servers timeout-ms]
                :or   {cwd         (System/getProperty "user.dir")
                       mcp-servers []
                       timeout-ms  30000}}]
   (let [result (client/await-result
                 (client/request! acp-client "session/new"
                                  {:cwd        cwd
                                   :mcpServers mcp-servers}
                                  {:timeout-ms timeout-ms})
                 timeout-ms)]
     {:session-id (:sessionId result)
      :client     acp-client})))

;; =============================================================================
;; prompt!
;;
;; This is the workhorse. It sends `session/prompt` and waits for
;; the response. Notifications (session/update) flow into the
;; client's on-event callback during the wait. This means the caller
;; gets a streaming experience without polling.
;; =============================================================================

(defn prompt!
  "Send a `session/prompt` and block until the response arrives.

   `content` is a vector of ACP content blocks
   (see `ai.brainyard.acp.interface/ContentBlock`). For text-only
   prompts use `(prompt-text! sess \"hello\")`.

   Returns a result map:
     {:stop-reason str           ;; \"end_turn\" | \"cancelled\" | …
      :raw         map           ;; full agent result
      :end-event   map}          ;; pre-translated end-of-turn event
                                 ;; (caller may forward to its own
                                 ;;  hook firing layer)

   Caller is responsible for firing the iteration/pre event before
   calling prompt! (use `iteration-pre-event` from `events.clj`).
   We do not fire it ourselves to keep `acp-client` independent of
   `agent`."
  ([sess content] (prompt! sess content {}))
  ([{:keys [session-id client] :as _sess} content {:keys [timeout-ms]
                                                   :or   {timeout-ms 600000}}]
   (let [result (client/await-result
                 (client/request! client "session/prompt"
                                  {:sessionId session-id
                                   :prompt    content}
                                  {:timeout-ms timeout-ms})
                 timeout-ms)
         stop  (:stopReason result)]
     {:stop-reason stop
      :raw         result
      :end-event   (events/translate-stop-reason stop session-id)})))

(defn prompt-text!
  "Convenience: send a single text block."
  ([sess text] (prompt-text! sess text {}))
  ([sess text opts]
   (prompt! sess [{:type "text" :text text}] opts)))

;; =============================================================================
;; cancel!
;; =============================================================================

(defn cancel!
  "Send `session/cancel` for an in-flight prompt. Returns once the
   agent acknowledges the cancel; the in-flight `prompt!` future will
   then resolve with `:stop-reason \"cancelled\"`."
  ([sess] (cancel! sess {}))
  ([{:keys [session-id client] :as _sess} {:keys [timeout-ms]
                                           :or   {timeout-ms 5000}}]
   (client/await-result
    (client/request! client "session/cancel" {:sessionId session-id}
                     {:timeout-ms timeout-ms})
    timeout-ms)))

;; =============================================================================
;; Iteration helpers — caller fires these via its own hook system if
;; one is wired up (Phase 5).
;; =============================================================================

(defn iteration-pre-event
  "Build the event descriptor for the start of an ACP turn."
  [sess prompt]
  (events/iteration-pre-event (:session-id sess) prompt))
