;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-oauth.core.discovery
  "Provider endpoint discovery, so endpoints are never hard-coded.

   Tries the OIDC document (`/.well-known/openid-configuration`) first, then
   the RFC 8414 OAuth 2.0 authorization-server metadata
   (`/.well-known/oauth-authorization-server`) — hosted MCP servers (OAuth 2.1)
   commonly publish only the latter. Results are cached per issuer for the
   process lifetime; flow selection keys off whether the metadata advertises a
   `device_authorization_endpoint`."
  (:require [ai.brainyard.clj-http-native.interface :as http]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(defonce ^:private cache (atom {}))

(def ^:private well-known-paths
  ["/.well-known/openid-configuration"
   "/.well-known/oauth-authorization-server"])

(defn ^:private issuer-base [issuer]
  (str/replace issuer #"/+$" ""))

(defn ^:private fetch-one [url]
  (try
    (let [resp (http/get url {:as :string
                              :timeout-ms 15000
                              :throw-exceptions false})]
      (when (<= 200 (:status resp) 299)
        (json/read-str (:body resp) :key-fn keyword)))
    (catch Exception e
      (mulog/debug ::discovery-fetch-failed :url url :message (.getMessage e))
      nil)))

(defn discover
  "Fetch + cache discovery metadata for `issuer`. Returns the metadata map
   (`:token_endpoint`, `:authorization_endpoint`, `:device_authorization_endpoint?`,
   `:grant_types_supported?`, …) or throws if no well-known document resolves."
  [issuer]
  (or (get @cache issuer)
      (let [base (issuer-base issuer)
            meta (some #(fetch-one (str base %)) well-known-paths)]
        (when-not meta
          (throw (ex-info "OAuth discovery failed: no well-known document at issuer"
                          {:issuer issuer :tried (map #(str base %) well-known-paths)})))
        (when-not (:token_endpoint meta)
          (throw (ex-info "OAuth discovery document missing token_endpoint"
                          {:issuer issuer})))
        (mulog/info ::discovered
                    :issuer issuer
                    :device? (boolean (:device_authorization_endpoint meta)))
        (swap! cache assoc issuer meta)
        meta)))

(defn supports-device-flow?
  "True when discovery metadata advertises a device authorization endpoint."
  [metadata]
  (boolean (:device_authorization_endpoint metadata)))

(defn clear-cache!
  "Drop cached discovery metadata (all issuers, or one). Mainly for tests."
  ([] (reset! cache {}))
  ([issuer] (swap! cache dissoc issuer)))
