;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.clj-llm.core.oauth
  "OAuth 2.0 with PKCE authentication for Anthropic Max/Pro plan subscription.

   Implements the authorization code flow with PKCE (Proof Key for Code Exchange)
   used by Anthropic's consumer subscription plans (Free/Pro/Max).
   This allows API access using a plan subscription instead of an API key.

   Flow:
   1. Generate PKCE code verifier + challenge
   2. Open browser to Anthropic's OAuth authorize endpoint
   3. User authorizes and gets redirected to console callback
   4. User pastes the callback URL (contains code#state)
   5. Exchange code + verifier for access token + refresh token
   6. Store tokens locally for reuse
   7. Auto-refresh expired tokens using refresh token

   IMPORTANT: As of January 2026, Anthropic restricts OAuth tokens from
   consumer plans (Free/Pro/Max) to Claude Code CLI and claude.ai only.
   Third-party API requests using these tokens will be rejected with:
   'This credential is only authorized for use with Claude Code.'
   This module is provided for reference and potential future use."
  (:require [ai.brainyard.clj-http-native.interface :as http]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.brainyard.mulog.interface :as mulog])
  (:import [java.security MessageDigest SecureRandom]
           [java.util Base64]
           [java.net URI]))

;; ============================================================================
;; Constants
;; ============================================================================

(def ^:private oauth-authorize-url "https://claude.ai/oauth/authorize")
(def ^:private oauth-token-url "https://console.anthropic.com/v1/oauth/token")
(def ^:private default-client-id "9d1c250a-e61b-44d9-88ed-5944d1962f5e")
(def ^:private default-scopes "org:create_api_key user:profile user:inference")
(def ^:private default-redirect-uri "https://console.anthropic.com/oauth/code/callback")

;; ============================================================================
;; PKCE Utilities
;; ============================================================================

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

;; ============================================================================
;; Token Storage
;; ============================================================================

(def ^:private token-file-path
  "Default path for storing OAuth tokens."
  (str (System/getProperty "user.home") "/.config/clj-llm/anthropic-oauth-tokens.json"))

(defn- ensure-parent-dirs [path]
  (let [parent (.getParentFile (io/file path))]
    (when-not (.exists parent)
      (.mkdirs parent))))

(defn save-tokens!
  "Save OAuth tokens to local storage."
  ([tokens] (save-tokens! tokens token-file-path))
  ([tokens path]
   (ensure-parent-dirs path)
   (spit path (json/write-str tokens))
   (mulog/debug ::tokens-saved :path path)
   tokens))

(defn load-tokens
  "Load OAuth tokens from local storage. Returns nil if not found."
  ([] (load-tokens token-file-path))
  ([path]
   (when (.exists (io/file path))
     (try
       (json/read-str (slurp path) :key-fn keyword)
       (catch Exception e
         (mulog/warn ::tokens-read-failed :path path :message (.getMessage e))
         nil)))))

(defn clear-tokens!
  "Remove stored OAuth tokens."
  ([] (clear-tokens! token-file-path))
  ([path]
   (let [f (io/file path)]
     (when (.exists f)
       (.delete f)
       (mulog/info ::tokens-cleared :path path)))))

;; ============================================================================
;; Token Validation & Refresh
;; ============================================================================

(defn token-expired?
  "Check if a token is expired or will expire within the next 60 seconds."
  [tokens]
  (if-let [expires-at (:expires_at tokens)]
    (< expires-at (+ (System/currentTimeMillis) 60000))
    true))

(defn refresh-access-token
  "Refresh an access token using a refresh token.
   Returns updated token map or throws on failure."
  [{:keys [refresh_token]} & {:keys [client-id]
                              :or {client-id default-client-id}}]
  (when-not refresh_token
    (throw (ex-info "No refresh token available. Re-authenticate with OAuth." {})))
  (mulog/info ::refreshing-access-token)
  (let [response (http/post oauth-token-url
                            {:body (json/write-str {:grant_type    "refresh_token"
                                                    :refresh_token refresh_token
                                                    :client_id     client-id})
                             :content-type :json
                             :as :string
                             :throw-exceptions true})
        body (json/read-str (:body response) :key-fn keyword)
        now (System/currentTimeMillis)
        tokens (assoc body
                      :expires_at (+ now (* (:expires_in body 3600) 1000))
                      :refresh_token (or (:refresh_token body) refresh_token)
                      :refreshed_at now)]
    (save-tokens! tokens)
    (mulog/info ::access-token-refreshed)
    tokens))

(defn get-valid-access-token
  "Get a valid access token, refreshing if necessary.
   Returns the access_token string or throws."
  ([] (get-valid-access-token {}))
  ([opts]
   (let [tokens (or (:tokens opts) (load-tokens))]
     (when-not tokens
       (throw (ex-info "No OAuth tokens found. Run (authenticate!) to log in." {})))
     (let [tokens (if (token-expired? tokens)
                    (refresh-access-token tokens opts)
                    tokens)]
       (:access_token tokens)))))

;; ============================================================================
;; Callback Response Parsing
;; ============================================================================

(defn parse-callback-response
  "Parse the callback URL or code#state string from the OAuth redirect.
   Anthropic returns the authorization code in the format 'code#state'
   either in the URL fragment or as a direct string.
   Returns {:code str :state str} or {:error str}."
  [response-str]
  (let [;; Extract the relevant part - could be a full URL or just code#state
        relevant (cond
                   ;; Full URL with query params
                   (str/includes? response-str "?code=")
                   (let [uri (URI. response-str)
                         query (.getQuery uri)]
                     (when query
                       (->> (str/split query #"&")
                            (map #(str/split % #"=" 2))
                            (into {}))))
                   ;; code#state format (from console callback page)
                   (str/includes? response-str "#")
                   (let [parts (str/split response-str #"#" 2)]
                     (when (= 2 (count parts))
                       {"code" (first parts) "state" (second parts)}))
                   ;; Just a code
                   :else {"code" response-str})]
    (if (and relevant (get relevant "code"))
      {:code (get relevant "code")
       :state (get relevant "state")}
      {:error "Could not parse authorization code from response"})))

;; ============================================================================
;; OAuth Flow
;; ============================================================================

(defn build-authorize-url
  "Build the OAuth authorization URL with PKCE parameters."
  [{:keys [client-id redirect-uri code-challenge state scopes]
    :or {client-id    default-client-id
         redirect-uri default-redirect-uri
         scopes       default-scopes}}]
  (str oauth-authorize-url
       "?code=true"
       "&client_id=" (java.net.URLEncoder/encode ^String client-id "UTF-8")
       "&response_type=code"
       "&redirect_uri=" (java.net.URLEncoder/encode ^String redirect-uri "UTF-8")
       "&scope=" (java.net.URLEncoder/encode ^String scopes "UTF-8")
       "&code_challenge=" (java.net.URLEncoder/encode ^String code-challenge "UTF-8")
       "&code_challenge_method=S256"
       "&state=" (java.net.URLEncoder/encode ^String state "UTF-8")))

(defn exchange-code-for-tokens
  "Exchange an authorization code for access + refresh tokens.
   The token exchange sends JSON body (not form-encoded)."
  [{:keys [code state code-verifier redirect-uri client-id]
    :or {client-id    default-client-id
         redirect-uri default-redirect-uri}}]
  (let [response (http/post oauth-token-url
                            {:body (json/write-str {:grant_type    "authorization_code"
                                                    :client_id     client-id
                                                    :code          code
                                                    :state         state
                                                    :code_verifier code-verifier
                                                    :redirect_uri  redirect-uri})
                             :content-type :json
                             :as :string
                             :throw-exceptions true})
        body (json/read-str (:body response) :key-fn keyword)
        now (System/currentTimeMillis)
        tokens (assoc body
                      :expires_at (+ now (* (:expires_in body 3600) 1000))
                      :authenticated_at now)]
    (save-tokens! tokens)
    (mulog/info ::oauth-tokens-obtained)
    tokens))

(defn authenticate!
  "Run the full OAuth 2.0 PKCE authentication flow.

   Opens a browser to Anthropic's login page. After authorizing,
   the user is redirected to console.anthropic.com with a code.
   The user pastes the code (or full callback URL) back into the terminal.

   Options:
     :client-id     - OAuth client ID (default: Anthropic's public client ID)
     :open-browser? - Whether to auto-open the browser (default: true)

   Returns the token map on success, throws on failure."
  [& {:keys [client-id open-browser?]
      :or {client-id     default-client-id
           open-browser? true}}]
  (let [code-verifier  (generate-code-verifier)
        code-challenge (generate-code-challenge code-verifier)
        state          (generate-state)
        auth-url       (build-authorize-url {:client-id      client-id
                                             :code-challenge code-challenge
                                             :state          state})]
    ;; Open browser
    (println "")
    (println "Opening browser for Anthropic authentication...")
    (println "If the browser doesn't open, visit this URL:")
    (println auth-url)
    (println "")
    (when open-browser?
      (try
        (let [desktop (java.awt.Desktop/getDesktop)]
          (.browse desktop (URI. auth-url)))
        (catch Exception _
          ;; Fallback: try xdg-open on Linux
          (try
            (.exec (Runtime/getRuntime) ^"[Ljava.lang.String;" (into-array String ["xdg-open" auth-url]))
            (catch Exception _
              (mulog/warn ::browser-open-failed :message "Could not open browser automatically"))))))
    ;; Wait for user to paste the code
    (println "After authorizing, paste the code (or full callback URL) here:")
    (print "> ")
    (flush)
    (let [input (str/trim (read-line))
          parsed (parse-callback-response input)]
      (if (:error parsed)
        (throw (ex-info (str "OAuth authentication failed: " (:error parsed))
                        {:error (:error parsed)}))
        (do
          ;; Validate state if present
          (when (and (:state parsed) (not= (:state parsed) state))
            (throw (ex-info "OAuth state mismatch — possible CSRF attack"
                            {:expected state :got (:state parsed)})))
          (exchange-code-for-tokens {:code          (:code parsed)
                                     :state         state
                                     :code-verifier code-verifier
                                     :client-id     client-id}))))))

;; ============================================================================
;; Integration Helpers
;; ============================================================================

(defn oauth-authenticated?
  "Check if we have valid (or refreshable) OAuth tokens stored."
  []
  (boolean (load-tokens)))

(defn get-oauth-headers
  "Build HTTP headers for an Anthropic API call using OAuth bearer token.
   Auto-refreshes the token if expired."
  ([] (get-oauth-headers {}))
  ([opts]
   (let [access-token (get-valid-access-token opts)]
     {"Authorization"    (str "Bearer " access-token)
      "anthropic-version" "2023-06-01"
      "Content-Type"     "application/json"})))

(defn logout!
  "Clear stored OAuth tokens (logout)."
  []
  (clear-tokens!)
  (println "Logged out from Anthropic OAuth."))
