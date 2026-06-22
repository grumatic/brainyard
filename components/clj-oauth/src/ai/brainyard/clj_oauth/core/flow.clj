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
            [ai.brainyard.clj-oauth.core.device :as device]
            [ai.brainyard.clj-oauth.core.discovery :as discovery]
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

(defn select-flow
  "Resolve the concrete flow keyword from the requested `flow` + discovery
   `metadata`. `:auto` → `:device` when a device endpoint is advertised, else
   `:paste` (never silently `:loopback`)."
  [flow metadata]
  (case (or flow :auto)
    :device :device
    :paste  :paste
    :loopback :loopback
    ;; :auto
    (if (discovery/supports-device-flow? metadata) :device :paste)))

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

(defn login!
  "One-call login. Resolves endpoints (explicit `:endpoints` or `:issuer`
   discovery), picks a flow, runs it, persists the bundle under `:account-id`,
   and returns it. See clj-oauth.interface/login! for the opts map."
  [{:keys [account-id issuer endpoints client-id scopes flow
           on-user-prompt on-status read-code]
    :or   {flow :auto scopes []}}]
  (when-not account-id
    (throw (ex-info "login! requires :account-id" {})))
  (let [metadata (cond endpoints endpoints
                       issuer    (discovery/discover issuer)
                       :else     (throw (ex-info "login! requires :issuer or :endpoints"
                                                 {:account-id account-id})))
        chosen   (select-flow flow metadata)
        _        (mulog/info ::login :account account-id :flow chosen)
        bundle   (case chosen
                   :device   (run-device metadata client-id scopes on-user-prompt on-status)
                   :paste    (authcode/paste-login!
                              {:authorization-endpoint (:authorization_endpoint metadata)
                               :token-endpoint         (:token_endpoint metadata)
                               :client-id              client-id
                               :scopes                 scopes
                               :on-user-prompt         on-user-prompt
                               :read-code              read-code})
                   :loopback (throw (ex-info "Loopback flow is not implemented in v1 — use :device or :paste"
                                             {:flow :loopback})))
        ;; Bake refresh metadata so access-token is one-arg later.
        enriched (assoc bundle
                        :token_endpoint (:token_endpoint metadata)
                        :client_id      client-id
                        :body_encoding  "form"
                        :scope          (str/join " " scopes))]
    (store/save-tokens! account-id enriched)
    enriched))

;; ============================================================================
;; Token access (refresh metadata comes from the stored bundle)
;; ============================================================================

(defn ^:private refresh-opts [bundle]
  {:token-endpoint (:token_endpoint bundle)
   :client-id      (:client_id bundle)
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
