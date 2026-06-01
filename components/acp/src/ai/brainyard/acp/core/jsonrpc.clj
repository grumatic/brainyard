;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.acp.core.jsonrpc
  "JSON-RPC 2.0 message encoding, decoding, and classification.

   This namespace is **pure data** — no I/O, no side-effects beyond a
   monotonic id counter. Transports (stdio, http) call `encode` /
   `decode` and route the resulting maps based on `classify`.

   Supports the three JSON-RPC 2.0 message kinds:

     :request       {:jsonrpc \"2.0\" :id N :method M :params P}
     :response      {:jsonrpc \"2.0\" :id N :result R}
                  | {:jsonrpc \"2.0\" :id N :error E}
     :notification  {:jsonrpc \"2.0\"        :method M :params P}

   The MCP client (components/agent/mcp/client.clj) embeds equivalent
   logic inline; this module factors it out so it can be reused by
   components/acp-client without dragging in agent semantics. A future
   refactor may move MCP onto this module — see docs/acp-design.md §9.2."
  (:require [clojure.data.json :as json])
  (:import [java.util.concurrent.atomic AtomicLong]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const JSON_RPC_VERSION "2.0")

;; Standard JSON-RPC 2.0 error codes (https://www.jsonrpc.org/specification#error_object)
(def error-codes
  {:parse-error      -32700
   :invalid-request  -32600
   :method-not-found -32601
   :invalid-params   -32602
   :internal-error   -32603})

;; =============================================================================
;; Request id allocation
;; =============================================================================

(defn make-id-source
  "Return a function that yields a fresh monotonically-increasing request id
   on each call. Each ACP client owns its own id source so concurrent clients
   don't share counters."
  []
  (let [counter (AtomicLong. 0)]
    (fn [] (.incrementAndGet counter))))

;; =============================================================================
;; Message constructors (pure)
;; =============================================================================

(defn request
  "Build a JSON-RPC request map. `id` is caller-supplied (typically from an
   id source). `params` may be nil."
  [id method params]
  (when-not (string? method)
    (throw (ex-info "method must be a string" {:method method})))
  (cond-> {:jsonrpc JSON_RPC_VERSION
           :id      id
           :method  method}
    (some? params) (assoc :params params)))

(defn notification
  "Build a JSON-RPC notification (a request without an `id`)."
  [method params]
  (when-not (string? method)
    (throw (ex-info "method must be a string" {:method method})))
  (cond-> {:jsonrpc JSON_RPC_VERSION
           :method  method}
    (some? params) (assoc :params params)))

(defn response
  "Build a JSON-RPC success response."
  [id result]
  {:jsonrpc JSON_RPC_VERSION
   :id      id
   :result  result})

(defn error-response
  "Build a JSON-RPC error response. `code` is an integer; `message` a string;
   `data` is optional and may be any JSON-encodable value."
  ([id code message]
   (error-response id code message nil))
  ([id code message data]
   {:jsonrpc JSON_RPC_VERSION
    :id      id
    :error   (cond-> {:code code :message message}
               (some? data) (assoc :data data))}))

;; =============================================================================
;; Encoding / decoding
;; =============================================================================

(defn encode
  "Serialize a JSON-RPC message map to a JSON string. The transport is
   responsible for framing (NDJSON appends a trailing newline)."
  [msg]
  (json/write-str msg))

(defn decode
  "Parse a JSON-RPC line into a Clojure map with keyword keys.

   Throws ExceptionInfo with `:type :parse-error` on malformed JSON."
  [^String line]
  (try
    (json/read-str line :key-fn keyword)
    (catch Exception e
      (throw (ex-info (str "JSON-RPC parse error: " (ex-message e))
                      {:type :parse-error
                       :line line}
                      e)))))

;; =============================================================================
;; Message classification
;; =============================================================================

(defn classify
  "Identify which kind of JSON-RPC message a parsed map represents.

   Returns one of :request | :response | :notification | :invalid.

   - request:      has both :method and :id
   - notification: has :method but no :id
   - response:     has :id and (:result OR :error) but no :method
   - invalid:      anything else"
  [msg]
  (when (map? msg)
    (let [has-method? (contains? msg :method)
          has-id?     (contains? msg :id)
          has-result? (contains? msg :result)
          has-error?  (contains? msg :error)]
      (cond
        (and has-method? has-id?)
        :request

        (and has-method? (not has-id?))
        :notification

        (and has-id? (or has-result? has-error?) (not has-method?))
        :response

        :else
        :invalid))))

(defn request? [msg] (= :request (classify msg)))
(defn response? [msg] (= :response (classify msg)))
(defn notification? [msg] (= :notification (classify msg)))

(defn error?
  "True if `msg` is a response carrying an `:error` field."
  [msg]
  (and (response? msg) (contains? msg :error)))
