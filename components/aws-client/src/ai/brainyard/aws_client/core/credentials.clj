;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.aws-client.core.credentials
  "AWS credentials management including static credentials,
   environment-based providers, and STS assume-role with auto-refresh."
  (:require [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as cred]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.data.json :as json]
            [taoensso.truss :refer [have have?]]))

;; ============================================================================
;; Credentials Providers
;; ============================================================================

(defn static-credentials-provider
  "Create a static credentials provider from access key and secret.

   Options:
   - :access-key-id - AWS access key ID
   - :secret-access-key - AWS secret access key
   - :session-token - (optional) AWS session token for temporary credentials"
  [{:keys [access-key-id secret-access-key session-token]}]
  (reify cred/CredentialsProvider
    (fetch [_]
      (cond-> {:aws/access-key-id (have string? access-key-id)
               :aws/secret-access-key (have string? secret-access-key)}
        session-token (assoc :aws/session-token session-token)))))

(defn env-credentials-provider
  "Create a credentials provider that reads from environment variables.
   Uses AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, and optionally AWS_SESSION_TOKEN."
  []
  (reify cred/CredentialsProvider
    (fetch [_]
      (let [access-key (System/getenv "AWS_ACCESS_KEY_ID")
            secret-key (System/getenv "AWS_SECRET_ACCESS_KEY")
            session-token (System/getenv "AWS_SESSION_TOKEN")]
        (when (and access-key secret-key)
          (cond-> {:aws/access-key-id access-key
                   :aws/secret-access-key secret-key}
            session-token (assoc :aws/session-token session-token)))))))

(defn profile-credentials-provider
  "Create a credentials provider using AWS profile.
   If profile-name is nil, uses the default profile."
  ([] (cred/profile-credentials-provider))
  ([profile-name]
   (cred/profile-credentials-provider profile-name)))

(defn chain-credentials-provider
  "Create a credentials provider that tries multiple providers in order.
   Returns the first successful credential fetch."
  [providers]
  (cred/chain-credentials-provider providers))

(defn default-credentials-provider
  "Create the default credentials provider chain that tries:
   1. Environment variables
   2. System properties
   3. Profile credentials
   4. Container credentials
   5. Instance profile credentials"
  []
  (cred/default-credentials-provider))

;; ============================================================================
;; STS Assume Role
;; ============================================================================

(defn- create-sts-client
  "Create an STS client with the given credentials provider and region."
  [credentials-provider region]
  (aws/client {:api :sts
               :region region
               :credentials-provider credentials-provider}))

(defn assume-role
  "Assume an IAM role and return temporary credentials.

   Parameters:
   - sts-client - An STS client
   - role-arn - The ARN of the role to assume
   - session-name - A name for the session

   Options:
   - :external-id - External ID for cross-account access
   - :duration-seconds - Duration for the credentials (default: 3600)

   Returns credentials map or throws on error."
  [sts-client role-arn session-name & {:keys [external-id duration-seconds]}]
  (let [request (cond-> {:RoleArn role-arn
                         :RoleSessionName session-name}
                  external-id (assoc :ExternalId external-id)
                  duration-seconds (assoc :DurationSeconds duration-seconds))
        response (aws/invoke sts-client {:op :AssumeRole
                                         :request request})]
    (mulog/debug ::assume-role-response :response response)
    (if (or (:ErrorResponse response)
            (:cognitect.anomalies/category response)
            (get-in response [:Response :Errors]))
      (throw (ex-info "Error assuming role!" {:response response
                                              :role-arn role-arn}))
      (let [creds (get response :Credentials)]
        (cred/valid-credentials
         {:aws/access-key-id (:AccessKeyId creds)
          :aws/secret-access-key (:SecretAccessKey creds)
          :aws/session-token (:SessionToken creds)
          ::cred/ttl (cred/calculate-ttl creds)}
         (format "STS assumed role (session-name: %s)" session-name))))))

(defn assume-role-chain
  "Assume a chain of roles, useful for cross-account access.

   Parameters:
   - initial-provider - Starting credentials provider
   - region - AWS region
   - role-arns - Vector of role ARNs to assume in order
                 Each element can be a string (role ARN) or a map with
                 :role-arn and optionally :role-external-id
   - session-name - Name for the session

   Returns the final credentials."
  [initial-provider region role-arns session-name]
  (mulog/debug ::assume-role-chain :role-arns role-arns)
  (when (empty? role-arns)
    (throw (ex-info "role-arns is empty!" {})))
  (loop [provider initial-provider
         roles (have vector? role-arns)
         final-creds nil]
    (let [client (create-sts-client provider region)
          role (first roles)
          [arn external-id] (if (map? role)
                              [(:role-arn role) (:role-external-id role)]
                              [role nil])
          creds (assume-role client arn session-name :external-id external-id)
          rest-roles (rest roles)]
      (if (seq rest-roles)
        (let [new-provider (reify cred/CredentialsProvider
                             (fetch [_] creds))]
          (recur new-provider rest-roles creds))
        creds))))

(defn assumed-role-credentials-provider
  "Create a credentials provider that assumes a role with auto-refresh.

   Parameters:
   - initial-provider - Starting credentials provider
   - region - AWS region
   - role-arns - Vector of role ARNs to assume
   - session-name - Name for the session

   Returns a cached credentials provider that auto-refreshes."
  [initial-provider region role-arns session-name]
  (cred/cached-credentials-with-auto-refresh
   (reify
     cred/CredentialsProvider
     (fetch [_]
       (assume-role-chain initial-provider region role-arns session-name))
     cred/Stoppable
     (-stop [_]))))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn stop-provider
  "Stop a credentials provider that implements Stoppable.
   Safe to call on any provider (no-op if not stoppable)."
  [provider]
  (when (satisfies? cred/Stoppable provider)
    (cred/stop provider)))

(defn credentials-valid?
  "Check if credentials are valid (non-nil access key and secret)."
  [{:keys [access-key-id secret-access-key]}]
  (and (some? access-key-id)
       (not (empty? access-key-id))
       (some? secret-access-key)
       (not (empty? secret-access-key))))
