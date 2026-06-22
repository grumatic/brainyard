;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-oauth.core.pkce
  "PKCE (RFC 7636) + CSRF-state helpers shared by every OAuth flow.

   Lifted verbatim from clj-llm/core/oauth.clj so device flow, the
   authorization-code paste fallback, and the Anthropic adapter all share one
   implementation. Pure JDK crypto (SecureRandom + Base64 + SHA-256) — no JNI
   or reflection, so it carries no extra native-image surface."
  (:import [java.security MessageDigest SecureRandom]
           [java.util Base64]))

(defn generate-code-verifier
  "Generate a cryptographically random code verifier (43-128 chars, URL-safe base64)."
  []
  (let [random (SecureRandom.)
        bytes (byte-array 32)]
    (.nextBytes random bytes)
    (-> (Base64/getUrlEncoder)
        (.withoutPadding)
        (.encodeToString bytes))))

(defn generate-code-challenge
  "Generate S256 code challenge from a code verifier."
  [code-verifier]
  (let [digest (MessageDigest/getInstance "SHA-256")
        hash (.digest digest (.getBytes ^String code-verifier "US-ASCII"))]
    (-> (Base64/getUrlEncoder)
        (.withoutPadding)
        (.encodeToString hash))))

(defn generate-state
  "Generate a random state parameter for CSRF protection."
  []
  (let [random (SecureRandom.)
        bytes (byte-array 16)]
    (.nextBytes random bytes)
    (-> (Base64/getUrlEncoder)
        (.withoutPadding)
        (.encodeToString bytes))))
