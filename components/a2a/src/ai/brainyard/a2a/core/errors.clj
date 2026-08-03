;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a.core.errors
  "A2A error catalog, and translation to/from brainyard's `{:error \"…\"}`
   convention.

   Two directions, both needed:

   - **Inbound** (client): a JSON-RPC error object off the wire becomes a
     brainyard error map that the LLM can read, via `->result`.
   - **Outbound** (server): a local failure becomes a conformant JSON-RPC
     error object, via `->jsonrpc`.

   ## Not leaking existence

   The specification requires that a caller must not be able to
   distinguish \"this task does not exist\" from \"this task exists but is
   not yours\" — otherwise the error channel becomes an enumeration
   oracle. `not-found` is the single constructor for both cases, and
   server handlers should reach for it rather than composing a bespoke
   403. This is enforced by convention, not by the compiler, so it is
   called out here and in docs/design/a2a-design.md §8."
  (:require [clojure.string :as str]
            [ai.brainyard.acp.interface :as acp]))

;; =============================================================================
;; Codes
;; =============================================================================

(def standard-codes
  "Standard JSON-RPC 2.0 codes, re-exported from the shared codec so there
   is one definition in the workspace."
  acp/error-codes)

(def a2a-codes
  "A2A-specific codes, in the JSON-RPC implementation-defined server range
   (-32000..-32099)."
  {:task-not-found                   -32001
   :task-not-cancelable              -32002
   :push-notification-not-supported  -32003
   :unsupported-operation            -32004
   :content-type-not-supported       -32005
   :invalid-agent-response           -32006
   :extended-card-not-configured     -32007
   ;; v1.0. Observed from a running a2a-sdk 1.1.0, which answers this when
   ;; the inbound A2A-Version is one it does not serve — including when the
   ;; header is ABSENT, since the spec reads an absent header as 0.3.
   :version-not-supported            -32009})

(def all-codes
  (merge standard-codes a2a-codes))

(def code->key
  "Reverse index: numeric code -> keyword."
  (into {} (map (fn [[k v]] [v k])) all-codes))

(def messages
  "Canonical human-readable message per error key. Servers may elaborate in
   `data`, but the `message` string stays stable so clients can match on it."
  {:parse-error                      "Invalid JSON payload"
   :invalid-request                  "Invalid JSON-RPC request"
   :method-not-found                 "Method not found"
   :invalid-params                   "Invalid parameters"
   :internal-error                   "Internal error"
   :task-not-found                   "Task not found"
   :task-not-cancelable              "Task cannot be canceled"
   :push-notification-not-supported  "Push Notification is not supported"
   :unsupported-operation            "This operation is not supported"
   :content-type-not-supported       "Incompatible content types"
   :invalid-agent-response           "Invalid agent response"
   :extended-card-not-configured     "Authenticated Extended Card is not configured"
   :version-not-supported            "A2A version is not supported by this handler"})

(defn code-of
  "Numeric code for an error keyword, or the internal-error code when the
   keyword is unknown."
  [k]
  (get all-codes k (:internal-error standard-codes)))

(defn message-of
  "Canonical message for an error keyword."
  [k]
  (get messages k "Internal error"))

;; =============================================================================
;; Outbound — local failure -> JSON-RPC error response
;; =============================================================================

(defn ->jsonrpc
  "Build a JSON-RPC error response for request `id` from an error keyword.

   `data` is optional and may carry structured detail (the spec suggests
   `google.rpc`-shaped entries). Do NOT put resource identity in `data`
   for authorization failures — see the ns docstring."
  ([id k]      (->jsonrpc id k nil))
  ([id k data] (acp/error-response id (code-of k) (message-of k) data)))

(defn not-found
  "The single constructor for both \"no such task\" and \"not yours\".

   Deliberately takes no resource identity: the response must be
   byte-identical in either case, and an API that accepted a task-id here
   would invite someone to echo it back into the error."
  [id]
  (->jsonrpc id :task-not-found))

(defn unsupported
  "The agent does not implement this operation — streaming when
   `capabilities.streaming` is false, an unexposed skill, a disabled
   push-notification family."
  ([id]        (->jsonrpc id :unsupported-operation))
  ([id detail] (->jsonrpc id :unsupported-operation {:detail detail})))

(defn invalid-params
  ([id]        (->jsonrpc id :invalid-params))
  ([id detail] (->jsonrpc id :invalid-params {:detail detail})))

(defn internal
  ([id]        (->jsonrpc id :internal-error))
  ([id detail] (->jsonrpc id :internal-error {:detail detail})))

;; =============================================================================
;; Inbound — JSON-RPC error object -> brainyard error map
;; =============================================================================

(defn error-key
  "Keyword for a JSON-RPC error object's numeric code, or `:unknown-error`."
  [err]
  (get code->key (:code err) :unknown-error))

(defn ->result
  "Convert a JSON-RPC error object into brainyard's `{:error \"…\"}` shape.

   The message is built to be useful to an LLM reading a tool result: the
   canonical text, the numeric code, and any `data` detail the server
   supplied. `:error-key` is carried alongside so calling code can branch
   on the failure kind without parsing prose."
  [err]
  (let [k      (error-key err)
        ;; `data` is spec'd as free-form. We emit `{:detail …}`; the Python
        ;; SDK emits a bare STRING; the spec's own examples use a vector of
        ;; `@type`-tagged objects. Reading only our own shape threw away the
        ;; other peer's diagnostic and reduced a precise parse error to
        ;; "Invalid params (-32602)" — which is how a v1.0 field mismatch
        ;; took a round of guessing to identify.
        detail (let [d (:data err)]
                 (cond
                   (string? d)     (not-empty d)
                   (:detail d)     (:detail d)
                   (sequential? d) (not-empty (str/join "; " (map pr-str d)))
                   (map? d)        (not-empty (pr-str d))
                   :else           nil))
        msg    (or (:message err) (message-of k))]
    {:error     (cond-> (str msg " (" (:code err) ")")
                  detail (str " — " detail))
     :error-key k
     :code      (:code err)}))

(defn retryable?
  "True when retrying the identical request could plausibly succeed.

   Deliberately narrow: only transport/internal failures qualify. A
   `task-not-found` or `unsupported-operation` will fail identically
   forever, and retrying it just spends someone's tokens twice."
  [err]
  (contains? #{:internal-error} (error-key err)))
