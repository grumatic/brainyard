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
  (:require [ai.brainyard.clj-oauth.core.discovery :as discovery]
            [ai.brainyard.clj-oauth.core.flow :as flow]
            [ai.brainyard.clj-oauth.core.pkce :as pkce]
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

(def token-usable?
  "True when account-id has a bundle that can yield a bearer WITHOUT re-login
   (present, and not-expired or refreshable). Prefer over `authenticated?` for
   skip-login decisions: a stale refresh-less bundle is authenticated but
   unusable. See store/token-usable?."
  store/token-usable?)

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

;; ============================================================================
;; Discovery + flow orchestration
;; ============================================================================

(def discover
  "Fetch + cache OIDC/OAuth-AS discovery metadata for an issuer.
   See discovery/discover."
  discovery/discover)

(def supports-device-flow?
  "True when discovery metadata advertises a device endpoint.
   See discovery/supports-device-flow?."
  discovery/supports-device-flow?)

(def login!
  "One-call login. Picks device | paste from requested :flow + provider caps,
   runs it, persists the bundle under :account-id, and returns it. Opts:
     {:account-id     storage key, e.g. \"notion\" or :anthropic
      :issuer         issuer base URL (→ discovery)   ; OR
      :endpoints      explicit discovery-shaped metadata map
      :client-id      OAuth client id
      :client-secret  confidential-client secret (optional; PKCE clients omit it,
                      but providers without PKCE — e.g. GitHub — require it)
      :scopes         vector of scope strings
      :flow           :auto | :device | :paste | :loopback   ; default :auto
      :on-user-prompt (fn [{:keys [verification_uri user_code
                                   verification_uri_complete expires_in]}] ...)
      :on-status      (fn [:pending|:slow-down|:authorized]) ; optional
      :read-code      (fn [] pasted-code-string)}      ; required for :paste
   See flow/login!."
  flow/login!)

(def access-token
  "Valid access-token string for account-id, refreshing within 60s of expiry
   from metadata baked into the stored bundle. See flow/access-token."
  flow/access-token)

(def bearer-headers
  "{\"Authorization\" \"Bearer <token>\"} for account-id. See flow/bearer-headers."
  flow/bearer-headers)

(def refresh!
  "Force a token refresh for account-id regardless of expiry (e.g. after a 401).
   See flow/refresh!."
  flow/refresh!)

;; ============================================================================
;; Configuration (driven from .brainyard/config.edn by the host)
;; ============================================================================

(def set-token-store!
  "Pin the token-store backend: :auto | :keychain | :file. See store/set-backend!."
  store/set-backend!)

(def set-default-flow!
  "Set the default flow for :auto logins: :auto | :device | :paste.
   See flow/set-default-flow!."
  flow/set-default-flow!)

(def logout!
  "Clear stored tokens for account-id (alias of clear-tokens!)."
  store/clear-tokens!)
