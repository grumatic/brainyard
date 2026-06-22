;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-oauth.interface
  "Public surface of the shared OAuth client.

   Scope today (Phase 1): PKCE/state helpers and the provider-agnostic,
   keychain-ready token store + refresh, extracted from clj-llm so both the
   LLM-provider auth path and the MCP HTTP transport share one implementation.

   The higher-level one-call orchestration described in docs/design/oauth.md
   §4.1 — `discover`, `login!`, `access-token`, `bearer-headers` — arrives with
   discovery + device flow (Phase 2) and MCP integration (front-loaded). This
   namespace is the stable seam those build on; the store keys by
   `(account-id, user-id)`.

   See docs/design/oauth.md."
  (:require [ai.brainyard.clj-oauth.core.pkce :as pkce]
            [ai.brainyard.clj-oauth.core.store :as store]))

;; ============================================================================
;; PKCE / CSRF state (RFC 7636)
;; ============================================================================

(def generate-code-verifier
  "Random URL-safe code verifier (43-128 chars). See pkce/generate-code-verifier."
  pkce/generate-code-verifier)

(def generate-code-challenge
  "S256 challenge for a verifier. See pkce/generate-code-challenge."
  pkce/generate-code-challenge)

(def generate-state
  "Random CSRF state parameter. See pkce/generate-state."
  pkce/generate-state)

;; ============================================================================
;; Token store — keyed by (account-id, user-id)
;; ============================================================================

(def token-file
  "`~/.brainyard/oauth/<user-id>/<account>.json` File for account-id."
  store/token-file)

(def save-tokens!
  "Persist a token bundle atomically (0600) for account-id. See store/save-tokens!."
  store/save-tokens!)

(def load-tokens
  "Load the token bundle for account-id, or nil. See store/load-tokens."
  store/load-tokens)

(def clear-tokens!
  "Delete the stored bundle for account-id (logout). See store/clear-tokens!."
  store/clear-tokens!)

(def authenticated?
  "True when a bundle is stored for account-id. See store/authenticated?."
  store/authenticated?)

;; ============================================================================
;; Validation & refresh (provider-agnostic; endpoint supplied by caller)
;; ============================================================================

(def token-expired?
  "True if a bundle is missing :expires_at or expires within 60s."
  store/token-expired?)

(def refresh-access-token
  "Refresh a bundle against a caller-supplied :token-endpoint/:client-id.
   See store/refresh-access-token."
  store/refresh-access-token)

(def get-valid-access-token
  "Valid access-token string for account-id, refreshing if stale.
   See store/get-valid-access-token."
  store/get-valid-access-token)
