;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.core.oauth
  "Anthropic Max/Pro OAuth adapter over the shared `clj-oauth` component.

   Anthropic's consumer subscription plans (Free/Pro/Max) use the OAuth 2.0
   authorization-code + PKCE flow; their console does not advertise a device
   endpoint, so this stays an authorization-code flow. What moved out is the
   undifferentiated machinery — PKCE, the token store, validation, and refresh
   now live in `ai.brainyard.clj-oauth` and are shared with the MCP HTTP
   transport. This namespace keeps only the Anthropic-specific pieces:
   endpoints/client-id, the authorize-URL builder, the console callback parser,
   and the browser-open + paste flow.

   Storage relocated from the old world-readable
   `~/.config/clj-llm/anthropic-oauth-tokens.json` to the hardened
   `~/.brainyard/oauth/<user-id>/anthropic.json` (0600) — see clj-oauth/store.
   Existing logins must re-authenticate once.

   IMPORTANT: As of January 2026, Anthropic restricts OAuth tokens from
   consumer plans (Free/Pro/Max) to Claude Code CLI and claude.ai only.
   Third-party API requests using these tokens will be rejected with:
   'This credential is only authorized for use with Claude Code.'
   This module is provided for reference and potential future use.

   The public surface (`authenticate!`, `get-oauth-headers`,
   `get-valid-access-token`, `oauth-authenticated?`, `logout!`) is unchanged
   for source compatibility (clj-llm/interface, core/llm.clj)."
  (:require [ai.brainyard.clj-http-native.interface :as http]
            [ai.brainyard.clj-oauth.interface :as oauth]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [ai.brainyard.mulog.interface :as mulog])
  (:import [java.net URI]))

;; ============================================================================
;; Constants
;; ============================================================================

(def ^:private oauth-authorize-url "https://claude.ai/oauth/authorize")
(def ^:private oauth-token-url "https://console.anthropic.com/v1/oauth/token")
(def ^:private default-client-id "9d1c250a-e61b-44d9-88ed-5944d1962f5e")
(def ^:private default-scopes "org:create_api_key user:profile user:inference")
(def ^:private default-redirect-uri "https://console.anthropic.com/oauth/code/callback")

;; account-id under which the shared store partitions Anthropic credentials.
(def ^:private account-id :anthropic)

;; Endpoint/client config threaded into clj-oauth's provider-agnostic refresh.
;; Anthropic's token endpoint takes a JSON body (not form-encoded).
(def ^:private refresh-opts
  {:token-endpoint oauth-token-url
   :client-id      default-client-id
   :body-encoding  :json})

;; ============================================================================
;; Token storage / validation / refresh — delegated to clj-oauth
;; ============================================================================

(defn save-tokens!
  "Persist Anthropic tokens to the shared store (0600)."
  [tokens]
  (oauth/save-tokens! account-id tokens))

(defn load-tokens
  "Load Anthropic tokens from the shared store, or nil."
  []
  (oauth/load-tokens account-id))

(defn clear-tokens!
  "Remove stored Anthropic tokens."
  []
  (oauth/clear-tokens! account-id))

(defn token-expired?
  "Check if a token is expired or will expire within 60s."
  [tokens]
  (oauth/token-expired? tokens))

(defn refresh-access-token
  "Refresh an Anthropic access token using its refresh token."
  [tokens & {:keys [client-id]}]
  (oauth/refresh-access-token account-id tokens
                              (cond-> refresh-opts
                                client-id (assoc :client-id client-id))))

(defn get-valid-access-token
  "Get a valid Anthropic access token, refreshing if necessary.
   0-arity matches the dynamic call site in core/llm.clj."
  ([] (get-valid-access-token {}))
  ([opts] (oauth/get-valid-access-token account-id (merge refresh-opts opts))))

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
;; OAuth Flow (Anthropic authorization-code + PKCE)
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
  (let [code-verifier  (oauth/generate-code-verifier)
        code-challenge (oauth/generate-code-challenge code-verifier)
        state          (oauth/generate-state)
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
  (oauth/authenticated? account-id))

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
