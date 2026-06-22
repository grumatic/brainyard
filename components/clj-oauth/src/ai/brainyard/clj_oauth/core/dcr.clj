;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-oauth.core.dcr
  "OAuth 2.0 Dynamic Client Registration (RFC 7591).

   Hosted MCP/OAuth servers increasingly expect a client to self-register at the
   provider's `registration_endpoint` rather than ship a static `client-id`.
   When `login!` is given an issuer but no client-id and discovery advertises a
   registration endpoint, we register once and cache the resulting `client_id`
   (in the token store, keyed by issuer) so it is reused across restarts.

   We register as a public client (`token_endpoint_auth_method none`) — device
   flow and PKCE need no client secret. `post-fn` is injectable for tests."
  (:require [ai.brainyard.clj-http-native.interface :as http]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(def ^:private device-grant "urn:ietf:params:oauth:grant-type:device_code")
(def ^:private oob-redirect "urn:ietf:wg:oauth:2.0:oob")

(defn register-client!
  "Register a public client at `registration-endpoint` (RFC 7591). Returns the
   registration response (at least `:client_id`). Throws on non-2xx.

   `:grant-types` should reflect what the provider advertises
   (`grant_types_supported` from discovery) so we don't request a grant it
   rejects — defaults to device + refresh. When `authorization_code` is among
   them, RFC 7591 requires `redirect_uris`; an out-of-band redirect is supplied
   if the caller gives none."
  [registration-endpoint {:keys [client-name redirect-uris grant-types scopes post-fn]
                          :or   {post-fn http/post}}]
  (let [grants     (vec (or (seq grant-types) [device-grant "refresh_token"]))
        auth-code? (some #{"authorization_code"} grants)
        redirects  (or (seq redirect-uris) (when auth-code? [oob-redirect]))
        body (json/write-str
              (cond-> {:client_name                (or client-name "Brainyard")
                       :token_endpoint_auth_method "none"
                       :grant_types                grants}
                (seq redirects) (assoc :redirect_uris (vec redirects))
                (seq scopes)    (assoc :scope (str/join " " scopes))))
        resp (post-fn registration-endpoint
                      {:body body :content-type :json :as :string :throw-exceptions false})]
    (if (<= 200 (:status resp) 299)
      (let [reg (json/read-str (:body resp) :key-fn keyword)]
        (when-not (:client_id reg)
          (throw (ex-info "Registration response missing client_id" {:body (:body resp)})))
        (mulog/info ::client-registered :endpoint registration-endpoint :client-id (:client_id reg))
        reg)
      (throw (ex-info "Dynamic client registration failed"
                      {:status (:status resp) :body (:body resp)})))))
