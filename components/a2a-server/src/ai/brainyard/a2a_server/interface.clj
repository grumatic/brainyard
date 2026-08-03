;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a-server.interface
  "Public API for the A2A server — brainyard as CALLEE.

   Start a listener that lets any conformant A2A client delegate work into
   this process:

       (a2a-server/start! service {:host \"127.0.0.1\" :port 41241})

   `service` is a map of injected capabilities, NOT a dependency on
   `components/agent`:

       {:card-fn       (fn [] <AgentCard>)                     ; required
        :ask-fn        (fn [req] -> outcome | {:error …})      ; required
        :auth-token    \"…\"                                    ; required
        :get-task-fn   (fn [task-id] -> <Task> | nil)          ; optional
        :cancel-fn     (fn [task-id] -> <Task> | {:error …})   ; optional
        :list-tasks-fn (fn [opts] -> {:tasks [] :next-page-token s})
        :extended-card-fn (fn [] <AgentCard>)                  ; optional
        :max-depth     3}

   Keeping the agent runtime behind function injection is what makes this
   component pure transport — and testable against a stub with no agent
   runtime at all. Same split as `acp` vs `acp-client`.

   Routes:

       GET  /.well-known/agent-card.json   public card (UNAUTHENTICATED, per spec)
       POST /a2a                           JSON-RPC; SSE for streaming methods
       GET  /a2a/agent-card                authenticated extended card

   **A token is required to start.** Inbound A2A executes prompts against
   this workspace, so `start!` refuses to bind without one rather than
   offering an unauthenticated mode. See docs/design/a2a-design.md §8."
  (:require [ai.brainyard.a2a-server.core.handlers :as handlers]
            [ai.brainyard.a2a-server.core.http :as http]
            [ai.brainyard.a2a-server.core.sse :as sse]))

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn start!
  "Start the listener. Returns `{:server :host :port :url :card-url}` or
   `{:error …}`. `:port 0` binds an ephemeral port and reports it back."
  ([service] (start! service {}))
  ([service opts] (http/start! service opts)))

(defn stop!
  "Stop a running server. Idempotent."
  [handle]
  (http/stop! handle))

(defn validate-service
  "Vector of problems with a service map (empty == usable). Use to fail
   with a clear message before attempting to bind."
  [service]
  (http/validate-service service))

(def ^{:doc "JSON-RPC endpoint path."} RPC_PATH http/RPC_PATH)
(def ^{:doc "Authenticated extended-card path."} EXTENDED_CARD_PATH http/EXTENDED_CARD_PATH)

;; =============================================================================
;; Dispatch (exposed for tests and embedding)
;; =============================================================================

(defn dispatch
  "Route one decoded JSON-RPC request to its handler, returning a JSON-RPC
   response map. Streaming methods are not routed here — see
   `streaming-method?`.

   `dialect` decides both the method vocabulary and the reply encoding;
   it defaults to v0.3 (what an unmarked request is assumed to speak)."
  ([service msg] (handlers/dispatch service msg))
  ([service msg dialect] (handlers/dispatch service msg dialect)))

(defn resolve-dialect
  "Decide a request's wire dialect from its `A2A-Version` header and method
   name, returning `[dialect method-kw]` or nil. The method name wins when
   the header is missing or contradicts it — the two vocabularies are
   disjoint, so the name is unambiguous."
  [version method]
  (handlers/resolve-dialect version method))

(defn streaming-method?
  "True for methods answered with an SSE frame sequence."
  [method]
  (handlers/streaming-method? method))

(defn check-chain!
  "Nil when an inbound request may proceed, else a JSON-RPC error response.
   Call BEFORE doing any work."
  [id metadata max-depth]
  (handlers/check-chain! id metadata max-depth))

(defn stream-turn!
  "Run one streaming turn, writing SSE frames via `write!`."
  [service id params write!]
  (sse/stream-turn! service id params write!))

(defn sse-frame
  "Encode a JSON-RPC result as an SSE frame."
  [id result]
  (sse/frame id result))
