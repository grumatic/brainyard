;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.aws-client.core.client
  "AWS client management with caching, multi-account support, and lifecycle management."
  (:require [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as cred]
            [cognitect.aws.client.protocol :as client.protocol]
            [ai.brainyard.mulog.interface :as mulog]
            [taoensso.truss :refer [have have?]]
            [ai.brainyard.aws-client.core.credentials :as credentials]))

;; ============================================================================
;; Client Registry
;; ============================================================================

;; Global registry for AWS clients.
;; Structure: {session-id {:clients {service-key {:default {region client}
;;                                                :accounts {account-id client}}}
;;                         :credentials-provider provider}}
(defonce ^:private !client-registry
  (atom {}))

(defn get-client-info
  "Get info from an AWS client.

   Parameters:
   - client - AWS client
   - key - Optional specific key to retrieve"
  [client & {:keys [key]}]
  (let [info (client.protocol/-get-info client)]
    (if key (get info key) info)))

;; ============================================================================
;; Client Creation
;; ============================================================================

(defn create-client
  "Create a new AWS client for the specified service.

   Parameters:
   - service-key - AWS service keyword (e.g., :s3, :ec2, :iam)
   - region - AWS region

   Options:
   - :credentials-provider - Custom credentials provider
   - :validate-requests? - Enable request validation (default: true)"
  [service-key region & {:keys [credentials-provider validate-requests?]
                         :or {validate-requests? true}}]
  (let [provider (or credentials-provider (cred/default-credentials-provider))
        client (aws/client {:api service-key
                            :region region
                            :credentials-provider provider})]
    (when validate-requests?
      (aws/validate-requests client true))
    client))

;; ============================================================================
;; Session-based Client Management
;; ============================================================================

(defn get-credentials-provider
  "Get or create a credentials provider for a session.

   Parameters:
   - session-id - Unique session identifier
   - credentials - Map with :aws/access-key-id and :aws/secret-access-key"
  [session-id credentials]
  (or (get-in @!client-registry [session-id :credentials-provider])
      (let [provider (credentials/static-credentials-provider
                      {:access-key-id (:aws/access-key-id credentials)
                       :secret-access-key (:aws/secret-access-key credentials)
                       :session-token (:aws/session-token credentials)})]
        (swap! !client-registry assoc-in [session-id :credentials-provider] provider)
        provider)))

(defn get-client
  "Get or create a cached AWS client for a session.

   Parameters:
   - session-id - Unique session identifier
   - service-key - AWS service keyword (e.g., :s3, :ec2)
   - credentials - Map with AWS credentials and region

   Options:
   - :account-id - For cross-account access
   - :region - Override default region
   - :role-arns - Vector of role ARNs to assume (for cross-account)"
  [session-id service-key credentials & {:keys [account-id region role-arns]}]
  (let [client-region (or region (:aws/region credentials))]
    (if (nil? account-id)
      ;; Default client (no cross-account)
      (or (get-in @!client-registry [session-id :clients service-key :default client-region])
          (let [provider (get-credentials-provider session-id credentials)
                client (create-client service-key client-region
                                      :credentials-provider provider)]
            (swap! !client-registry assoc-in
                   [session-id :clients service-key :default client-region] client)
            client))
      ;; Cross-account client
      (or (get-in @!client-registry [session-id :clients service-key :accounts account-id])
          (let [base-provider (get-credentials-provider session-id credentials)
                provider (if (seq role-arns)
                           (credentials/assumed-role-credentials-provider
                            base-provider client-region role-arns
                            (format "session-%s-%s" session-id account-id))
                           base-provider)
                client (create-client service-key client-region
                                      :credentials-provider provider)]
            (swap! !client-registry assoc-in
                   [session-id :clients service-key :accounts account-id] client)
            client)))))

(defn reset-client
  "Reset (stop and remove) a cached client.

   Parameters:
   - session-id - Unique session identifier
   - service-key - AWS service keyword

   Options:
   - :account-id - Specific account to reset (nil for default client)
   - :region - Specific region to reset"
  ([session-id service-key]
   (reset-client session-id service-key nil))
  ([session-id service-key account-id]
   (if (nil? account-id)
     ;; Reset default client(s)
     (when-let [default-clients (get-in @!client-registry
                                        [session-id :clients (have keyword? service-key) :default])]
       (swap! !client-registry update-in [session-id :clients service-key :default] (constantly nil))
       (if (map? default-clients)
         (doseq [client (vals default-clients)
                 :when (some? client)]
           (aws/stop client))
         (aws/stop default-clients)))
     ;; Reset account-specific client
     (when-let [client (get-in @!client-registry
                               [session-id :clients (have keyword? service-key)
                                :accounts (have string? account-id)])]
       (let [provider (get-client-info client :key :credentials-provider)]
         (swap! !client-registry update-in
                [session-id :clients service-key :accounts account-id] (constantly nil))
         (when provider
           (credentials/stop-provider provider))
         (aws/stop client)
         ;; Also reset STS and IAM clients used for role assumption
         (reset-client session-id :sts)
         (reset-client session-id :iam))))))

(defn reset-all-clients
  "Reset all cached clients for a session."
  [session-id]
  (let [clients (get-in @!client-registry [session-id :clients])]
    (doseq [service (keys clients)
            :when (and service (keyword? service))
            :let [accounts (keys (get-in clients [service :accounts]))]]
      (reset-client session-id service)
      (doseq [account-id accounts
              :when account-id]
        (reset-client session-id service account-id)))))

(defn clear-session
  "Clear all state for a session."
  [session-id]
  (reset-all-clients session-id)
  (when-let [provider (get-in @!client-registry [session-id :credentials-provider])]
    (credentials/stop-provider provider))
  (swap! !client-registry dissoc session-id))

;; ============================================================================
;; Invocation Helpers
;; ============================================================================

(defmacro with-invoke-error-handler
  "Execute an AWS invoke with error handling.

   Parameters:
   - invoke-code - The aws/invoke call
   - clean-up-code - Code to run on error (e.g., reset client)
   - error-msg - Error message for the exception"
  [invoke-code clean-up-code error-msg]
  `(doto (try
           ~invoke-code
           (catch Exception e#
             {:ErrorResponse {:Error {:Message (ex-message e#) :Data (ex-data e#)}}}))
     (#(when (or (:ErrorResponse %)
                 (:cognitect.anomalies/category %)
                 (get-in % [:Response :Errors]))
         ~clean-up-code
         (throw (ex-info ~error-msg %))))))

(defn invoke
  "Invoke an AWS operation with standard error checking.

   Parameters:
   - client - AWS client
   - op - Operation keyword (e.g., :ListBuckets)
   - request - Request map

   Returns the response or throws on error."
  [client op request]
  (let [response (aws/invoke client {:op op :request request})]
    (if (or (:ErrorResponse response)
            (:cognitect.anomalies/category response)
            (get-in response [:Response :Errors]))
      (throw (ex-info (format "AWS %s error" (name op)) response))
      response)))

(defn invoke-safe
  "Invoke an AWS operation, returning nil on error instead of throwing.

   Parameters:
   - client - AWS client
   - op - Operation keyword
   - request - Request map

   Returns the response or nil on error."
  [client op request]
  (try
    (let [response (aws/invoke client {:op op :request request})]
      (when-not (or (:ErrorResponse response)
                    (:cognitect.anomalies/category response)
                    (get-in response [:Response :Errors]))
        response))
    (catch Exception e
      (mulog/warn ::invoke-safe-failed :message (ex-message e))
      nil)))

;; ============================================================================
;; Service Discovery
;; ============================================================================

(defn list-operations
  "List available operations for a client."
  [client]
  (aws/ops client))

(defn describe-operation
  "Get documentation for a specific operation."
  [client op]
  (aws/doc client op))
