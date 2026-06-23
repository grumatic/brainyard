;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-oauth.core.flow
  "Flow orchestration: pick device | paste | loopback from the requested flow +
   provider capabilities + environment, run it, and persist the result so later
   `access-token` calls can refresh without re-supplying endpoints.

   Default is device flow (docs/design/oauth.md §5). For `:auto` we run
   discovery: a `device_authorization_endpoint` → device; otherwise the headless
   authorization-code paste path. Loopback is never the silent default — it
   requires explicit opt-in (`:flow :loopback`), and v1 doesn't implement it.

   The persisted bundle carries `:token_endpoint`/`:client_id`/`:body_encoding`
   (device + paste both refresh form-encoded) so `access-token`/`bearer-headers`
   are one-arg by account-id."
  (:require [ai.brainyard.clj-oauth.core.authcode :as authcode]
            [ai.brainyard.clj-oauth.core.dcr :as dcr]
            [ai.brainyard.clj-oauth.core.device :as device]
            [ai.brainyard.clj-oauth.core.discovery :as discovery]
            [ai.brainyard.clj-oauth.core.loopback :as loopback]
            [ai.brainyard.clj-oauth.core.store :as store]
            [ai.brainyard.env-detect.interface :as env-detect]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str]))

;; ============================================================================
;; Environment probe
;; ============================================================================

(defn ^:private env-nonempty [k]
  (some-> (System/getenv k) str/trim not-empty))

(defn ^:private xdg-open-available? []
  (boolean (some #(and (= "xdg-open" (:name %)) (:available? %))
                 (try (env-detect/detect-executables) (catch Throwable _ nil)))))

(defn ^:private desktop-supported? []
  (try (java.awt.Desktop/isDesktopSupported) (catch Throwable _ false)))

(defn headless?
  "True when no local browser is reachable — a container/SSH session with no
   `$DISPLAY`/`$WAYLAND_DISPLAY`, no `xdg-open`, and no AWT Desktop. Drives the
   `:auto` fallback away from anything that wants to pop a browser."
  []
  (let [sandbox (:sandbox-type (try (env-detect/detect-sandbox-environment)
                                    (catch Throwable _ nil)))
        display? (or (env-nonempty "DISPLAY") (env-nonempty "WAYLAND_DISPLAY"))]
    (cond
      (contains? #{:docker :devcontainer} sandbox) true
      (and display? (or (desktop-supported?) (xdg-open-available?))) false
      (or (desktop-supported?) (xdg-open-available?)) false
      :else true)))

;; ============================================================================
;; Flow selection
;; ============================================================================

(defonce ^:private !default-flow
  ;; Config-supplied default (`:oauth-flow`) applied when the caller requests
  ;; `:auto`. nil → no override (pure auto-detect).
  (atom nil))

(defn set-default-flow!
  "Set the default flow used when a login requests `:auto` (from
   `.brainyard/config.edn :oauth-flow`). `:auto`/nil clears the override."
  [f]
  (let [k (some-> f (#(if (keyword? %) % (keyword (str %)))))]
    (reset! !default-flow (when (and k (not= :auto k)) k))))

(defn select-flow
  "Resolve the concrete flow keyword from the requested `flow` + discovery
   `metadata`. An explicit flow wins; `:auto` honors the config default
   (`set-default-flow!`) and otherwise picks `:device` when a device endpoint is
   advertised, else `:paste` (never silently `:loopback`)."
  [flow metadata]
  (let [flow (or flow :auto)
        flow (if (= :auto flow) (or @!default-flow :auto) flow)]
    (case flow
      :device   :device
      :paste    :paste
      :loopback :loopback
      ;; :auto
      (if (discovery/supports-device-flow? metadata) :device :paste))))

;; ============================================================================
;; Login
;; ============================================================================

(defn ^:private run-device [metadata client-id scopes on-user-prompt on-status]
  (let [da (device/start! {:device-authorization-endpoint (:device_authorization_endpoint metadata)
                           :client-id client-id
                           :scopes    scopes})]
    (when on-user-prompt
      (on-user-prompt (select-keys da [:verification_uri :verification_uri_complete
                                       :user_code :expires_in])))
    (device/poll! {:token-endpoint (:token_endpoint metadata)
                   :client-id      client-id
                   :device-code    (:device_code da)
                   :interval       (:interval da 5)
                   :expires-in     (:expires_in da)
                   :on-status      on-status})))

(defn ^:private client-account
  "Token-store key under which a dynamically-registered client_id is cached,
   keyed by issuer so it is reused across logins/restarts."
  [issuer]
  (str "client@" issuer))

(defn resolve-client-id!
  "Return a client_id: the explicit one, else a cached DCR registration for the
   issuer, else register dynamically (RFC 7591) when the provider advertises a
   `registration_endpoint` and cache it. `:redirect-uris` (loopback callback) is
   passed to DCR when present. Throws when none is available.

   NOTE: a cached registration is reused only when no redirect-uris are needed
   (device/paste); loopback always re-registers so the dynamic callback port is
   on the client — it can't be cached across runs."
  [{:keys [client-id issuer scopes redirect-uris]} metadata]
  (or client-id
      (when issuer
        (or (when-not (seq redirect-uris)
              (:client_id (store/load-tokens (client-account issuer))))
            (when-let [reg-ep (:registration_endpoint metadata)]
              (let [reg (dcr/register-client! reg-ep
                                              {:scopes scopes
                                               :grant-types (:grant_types_supported metadata)
                                               :redirect-uris redirect-uris})]
                (when-not (seq redirect-uris)
                  (store/save-tokens! (client-account issuer)
                                      (select-keys reg [:client_id :client_secret])))
                (:client_id reg)))))
      (throw (ex-info "No :client-id supplied and the provider offers no registration_endpoint (RFC 7591)"
                      {:issuer issuer}))))

(defn login!
  "One-call login. Resolves endpoints (explicit `:endpoints` or `:issuer`
   discovery), picks a flow, runs it, persists the bundle under `:account-id`,
   and returns it. See clj-oauth.interface/login! for the opts map."
  [{:keys [account-id issuer endpoints client-id client-secret scopes flow
           on-user-prompt on-status read-code open-browser-fn]
    :or   {flow :auto scopes []}}]
  (when-not account-id
    (throw (ex-info "login! requires :account-id" {})))
  (let [metadata  (cond endpoints endpoints
                        issuer    (discovery/discover issuer)
                        :else     (throw (ex-info "login! requires :issuer or :endpoints"
                                                  {:account-id account-id})))
        chosen    (select-flow flow metadata)
        _         (mulog/info ::login :account account-id :flow chosen)
        ;; Loopback needs its callback port BEFORE DCR (the redirect_uri is
        ;; registered on the client), so allocate it first and stop it after.
        cb        (when (= chosen :loopback) (loopback/start-callback-server!))]
    (try
      (let [client-id (resolve-client-id! {:client-id client-id :issuer issuer :scopes scopes
                                           :redirect-uris (when cb [(:redirect-uri cb)])}
                                          metadata)
            bundle    (case chosen
                        :device   (run-device metadata client-id scopes on-user-prompt on-status)
                        :paste    (authcode/paste-login!
                                   {:authorization-endpoint (:authorization_endpoint metadata)
                                    :token-endpoint         (:token_endpoint metadata)
                                    :client-id              client-id
                                    :client-secret          client-secret
                                    :scopes                 scopes
                                    :on-user-prompt         on-user-prompt
                                    :read-code              read-code})
                        :loopback (loopback/loopback-login!
                                   (cond-> {:authorization-endpoint (:authorization_endpoint metadata)
                                            :token-endpoint         (:token_endpoint metadata)
                                            :client-id              client-id
                                            :client-secret          client-secret
                                            :scopes                 scopes
                                            :redirect-uri           (:redirect-uri cb)
                                            :on-user-prompt         on-user-prompt
                                            :await-fn               (:await cb)}
                                     open-browser-fn (assoc :open-browser-fn open-browser-fn))))
            ;; Bake refresh metadata so access-token is one-arg later. A
            ;; confidential client's secret is persisted too (0600 token file)
            ;; so refresh can re-authenticate; omitted for public/PKCE clients.
            enriched  (cond-> (assoc bundle
                                     :token_endpoint (:token_endpoint metadata)
                                     :client_id      client-id
                                     :body_encoding  "form"
                                     :scope          (str/join " " scopes))
                        client-secret (assoc :client_secret client-secret))]
        (store/save-tokens! account-id enriched)
        enriched)
      (finally (when cb ((:stop! cb)))))))

;; ============================================================================
;; Token access (refresh metadata comes from the stored bundle)
;; ============================================================================

(defn ^:private refresh-opts [bundle]
  {:token-endpoint (:token_endpoint bundle)
   :client-id      (:client_id bundle)
   :client-secret  (:client_secret bundle)
   :body-encoding  (if (= "form" (:body_encoding bundle)) :form :json)})

(defn access-token
  "Valid access-token string for `account-id`, transparently refreshing when
   within 60s of expiry using the metadata baked into the stored bundle.
   Throws when no bundle is stored."
  [account-id]
  (let [bundle (store/load-tokens account-id)]
    (when-not bundle
      (throw (ex-info "Not authenticated" {:account-id account-id})))
    (store/get-valid-access-token account-id (assoc (refresh-opts bundle) :tokens bundle))))

(defn bearer-headers
  "`{\"Authorization\" \"Bearer <token>\"}` for `account-id`, refreshing as needed."
  [account-id]
  {"Authorization" (str "Bearer " (access-token account-id))})

(defn refresh!
  "Force a token refresh for `account-id` regardless of expiry — e.g. after a
   server-side 401 on a not-yet-expired token. Returns the new access token;
   throws when not authenticated or no refresh token is stored."
  [account-id]
  (let [bundle (store/load-tokens account-id)]
    (when-not bundle
      (throw (ex-info "Not authenticated" {:account-id account-id})))
    (:access_token (store/refresh-access-token account-id bundle (refresh-opts bundle)))))
