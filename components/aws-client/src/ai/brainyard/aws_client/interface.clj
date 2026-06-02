;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.aws-client.interface
  "Public API for the aws-client component.

   Provides AWS SDK infrastructure including:
   - Credentials management (static, environment, profile, assume-role)
   - Client creation and caching
   - Session-based multi-account support
   - Error handling utilities"
  (:require [ai.brainyard.aws-client.core.credentials :as credentials]
            [ai.brainyard.aws-client.core.client :as client]))

;; ============================================================================
;; Credentials Providers
;; ============================================================================

(defn static-credentials-provider
  "Create a static credentials provider.

   Options map:
   - :access-key-id - AWS access key ID (required)
   - :secret-access-key - AWS secret access key (required)
   - :session-token - AWS session token (optional)

   Example:
   (static-credentials-provider {:access-key-id \"AKIA...\"
                                 :secret-access-key \"...\"})"
  [opts]
  (credentials/static-credentials-provider opts))

(defn env-credentials-provider
  "Create a credentials provider that reads from environment variables.
   Uses AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, and optionally AWS_SESSION_TOKEN."
  []
  (credentials/env-credentials-provider))

(defn profile-credentials-provider
  "Create a credentials provider using AWS profile.

   When called with no args, uses the default profile.
   When called with profile-name, uses that specific profile."
  ([] (credentials/profile-credentials-provider))
  ([profile-name] (credentials/profile-credentials-provider profile-name)))

(defn chain-credentials-provider
  "Create a credentials provider that tries multiple providers in order.
   Returns the first successful credential fetch."
  [providers]
  (credentials/chain-credentials-provider providers))

(defn default-credentials-provider
  "Create the default credentials provider chain that tries:
   1. Environment variables
   2. System properties
   3. Profile credentials
   4. Container credentials
   5. Instance profile credentials"
  []
  (credentials/default-credentials-provider))

;; ============================================================================
;; STS Assume Role
;; ============================================================================

(defn assume-role
  "Assume an IAM role and return temporary credentials.

   Parameters:
   - sts-client - An STS client (create with create-client)
   - role-arn - The ARN of the role to assume
   - session-name - A name for the session

   Options:
   - :external-id - External ID for cross-account access
   - :duration-seconds - Duration for the credentials (default: 3600)

   Returns credentials map or throws on error."
  [sts-client role-arn session-name & {:as opts}]
  (apply credentials/assume-role sts-client role-arn session-name (mapcat identity opts)))

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
  (credentials/assume-role-chain initial-provider region role-arns session-name))

(defn assumed-role-credentials-provider
  "Create a credentials provider that assumes a role with auto-refresh.

   Parameters:
   - initial-provider - Starting credentials provider
   - region - AWS region
   - role-arns - Vector of role ARNs to assume
   - session-name - Name for the session

   Returns a cached credentials provider that auto-refreshes."
  [initial-provider region role-arns session-name]
  (credentials/assumed-role-credentials-provider
   initial-provider region role-arns session-name))

(defn stop-credentials-provider
  "Stop a credentials provider that implements Stoppable.
   Safe to call on any provider (no-op if not stoppable)."
  [provider]
  (credentials/stop-provider provider))

(defn credentials-valid?
  "Check if credentials are valid (non-nil access key and secret)."
  [credentials]
  (credentials/credentials-valid? credentials))

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
   - :validate-requests? - Enable request validation (default: true)

   Example:
   (create-client :s3 \"us-east-1\")
   (create-client :ec2 \"us-west-2\" :credentials-provider my-provider)"
  [service-key region & {:as opts}]
  (apply client/create-client service-key region (mapcat identity opts)))

(defn get-client-info
  "Get info from an AWS client.

   Parameters:
   - client - AWS client
   - key - Optional specific key to retrieve

   Example:
   (get-client-info my-client)
   (get-client-info my-client :key :credentials-provider)"
  [client & {:as opts}]
  (apply client/get-client-info client (mapcat identity opts)))

;; ============================================================================
;; Session-based Client Management
;; ============================================================================

(defn get-session-client
  "Get or create a cached AWS client for a session.

   Parameters:
   - session-id - Unique session identifier
   - service-key - AWS service keyword (e.g., :s3, :ec2)
   - credentials - Map with AWS credentials:
     - :aws/access-key-id
     - :aws/secret-access-key
     - :aws/region

   Options:
   - :account-id - For cross-account access
   - :region - Override default region
   - :role-arns - Vector of role ARNs to assume (for cross-account)

   Example:
   (get-session-client \"user-123\" :s3 my-creds)
   (get-session-client \"user-123\" :ec2 my-creds :account-id \"123456789012\")"
  [session-id service-key credentials & {:as opts}]
  (apply client/get-client session-id service-key credentials (mapcat identity opts)))

(defn reset-session-client
  "Reset (stop and remove) a cached client.

   Parameters:
   - session-id - Unique session identifier
   - service-key - AWS service keyword
   - account-id - Optional specific account (nil for default client)"
  ([session-id service-key]
   (client/reset-client session-id service-key))
  ([session-id service-key account-id]
   (client/reset-client session-id service-key account-id)))

(defn reset-all-session-clients
  "Reset all cached clients for a session."
  [session-id]
  (client/reset-all-clients session-id))

(defn clear-session
  "Clear all state for a session including credentials and clients."
  [session-id]
  (client/clear-session session-id))

;; ============================================================================
;; Invocation Helpers
;; ============================================================================

(defmacro with-invoke-error-handler
  "Execute an AWS invoke with error handling.

   Parameters:
   - invoke-code - The aws/invoke call
   - clean-up-code - Code to run on error (e.g., reset client)
   - error-msg - Error message for the exception

   Example:
   (with-invoke-error-handler
     (aws/invoke client {:op :ListBuckets})
     (reset-session-client session-id :s3)
     \"Failed to list buckets\")"
  [invoke-code clean-up-code error-msg]
  `(client/with-invoke-error-handler ~invoke-code ~clean-up-code ~error-msg))

(defn invoke
  "Invoke an AWS operation with standard error checking.

   Parameters:
   - client - AWS client
   - op - Operation keyword (e.g., :ListBuckets)
   - request - Request map

   Returns the response or throws on error.

   Example:
   (invoke s3-client :ListBuckets {})"
  [client op request]
  (client/invoke client op request))

(defn invoke-safe
  "Invoke an AWS operation, returning nil on error instead of throwing.

   Parameters:
   - client - AWS client
   - op - Operation keyword
   - request - Request map

   Returns the response or nil on error."
  [client op request]
  (client/invoke-safe client op request))

;; ============================================================================
;; Service Discovery
;; ============================================================================

(defn list-operations
  "List available operations for a client."
  [client]
  (client/list-operations client))

(defn describe-operation
  "Get documentation for a specific operation."
  [client op]
  (client/describe-operation client op))
