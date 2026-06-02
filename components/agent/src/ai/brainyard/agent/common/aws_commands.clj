;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.aws-commands
  "AWS credential management commands for agent system.

   Provides commands for agents to manage local AWS credentials:
   - aws$list-profiles: Discover available AWS profiles
   - aws$whoami: Verify current AWS identity via STS
   - aws$get-profile: Get details for a single profile
   - aws$set-profile: Switch active AWS profile for MCP servers

   Lives under common/ rather than mcp/ because AWS credential management is
   a general agent capability, not part of the MCP plumbing — the only
   MCP-specific bit is `aws$set-profile`'s side-effect of restarting any
   running aws-knowledge MCP server."
  (:require [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.agent.mcp.integration :as mcp-int]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [ai.brainyard.mulog.interface :as mulog]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- parse-ini-file
  "Parse an INI-style file into {section-name {key value}}.
   Returns empty map if file doesn't exist."
  [path]
  (let [f (io/file (str/replace path "~" (System/getProperty "user.home")))]
    (if (.exists f)
      (let [lines (str/split-lines (slurp f))]
        (loop [remaining lines
               current-section nil
               result {}]
          (if (empty? remaining)
            result
            (let [line (str/trim (first remaining))
                  rest-lines (rest remaining)]
              (cond
                ;; blank or comment
                (or (str/blank? line) (str/starts-with? line "#") (str/starts-with? line ";"))
                (recur rest-lines current-section result)

                ;; section header
                (and (str/starts-with? line "[") (str/ends-with? line "]"))
                (let [section (subs line 1 (dec (count line)))]
                  (recur rest-lines section (assoc result section (get result section {}))))

                ;; key = value
                (str/includes? line "=")
                (let [idx (str/index-of line "=")
                      k (str/trim (subs line 0 idx))
                      v (str/trim (subs line (inc idx)))]
                  (if current-section
                    (recur rest-lines current-section
                           (assoc-in result [current-section k] v))
                    (recur rest-lines current-section result)))

                :else
                (recur rest-lines current-section result))))))
      {})))

(defn- merge-aws-profiles
  "Merge credentials and config files, normalizing 'profile foo' -> 'foo'.
   Returns {profile-name {:region ... :aws_access_key_id ... :source ...}}."
  [credentials config]
  (let [normalize-section (fn [section-name]
                            (if (str/starts-with? section-name "profile ")
                              (subs section-name (count "profile "))
                              section-name))
        ;; Normalize config sections
        normalized-config (reduce-kv
                           (fn [acc k v]
                             (assoc acc (normalize-section k) v))
                           {} config)
        ;; Merge: credentials take precedence for keys
        all-profiles (set (concat (keys credentials) (keys normalized-config)))]
    (into {}
          (for [profile all-profiles]
            (let [cred-data (get credentials profile {})
                  conf-data (get normalized-config profile {})
                  merged (merge conf-data cred-data)
                  source (cond
                           (get merged "aws_access_key_id") "static-keys"
                           (get merged "sso_start_url")     "sso"
                           (get merged "role_arn")           "assume-role"
                           (get merged "credential_process") "credential-process"
                           (get merged "sso_session")        "sso-session"
                           :else                             "unknown")]
              [profile (assoc merged "_source" source)])))))

(defn- mask-value
  "Mask a string, showing only the last 4 characters.
   Returns nil if input is nil."
  [s]
  (when s
    (let [s (str s)]
      (if (<= (count s) 4)
        "****"
        (str (apply str (repeat (- (count s) 4) "*")) (subs s (- (count s) 4)))))))

(defn- run-aws-cli
  "Run an AWS CLI command via ProcessBuilder.
   Returns {:exit int :out string :err string}."
  [args & {:keys [env timeout-ms] :or {timeout-ms 10000}}]
  (try
    (let [pb (ProcessBuilder. ^java.util.List (into ["aws"] args))
          env-map (.environment pb)]
      (when env
        (doseq [[k v] env]
          (.put env-map k v)))
      (.redirectErrorStream pb false)
      (let [proc (.start pb)
            out (future (slurp (.getInputStream proc)))
            err (future (slurp (.getErrorStream proc)))
            ;; (long ...) cast: Process.waitFor has two overloads —
            ;; `int waitFor()` and `boolean waitFor(long, TimeUnit)`.
            ;; Without a primitive long hint Clojure dispatches via
            ;; reflection at runtime, which native-image strips →
            ;; silent failure. See docs/design/native-image-design.md §11.
            exited (.waitFor proc (long timeout-ms) java.util.concurrent.TimeUnit/MILLISECONDS)]
        (if exited
          {:exit (.exitValue proc) :out @out :err @err}
          (do (.destroyForcibly proc)
              {:exit -1 :out "" :err "Command timed out"}))))
    (catch Exception e
      {:exit -1 :out "" :err (ex-message e)})))

;; ============================================================================
;; Commands
;; ============================================================================

(defcommand aws$list-profiles
  "List available AWS profiles from ~/.aws/credentials and ~/.aws/config."
  (fn [& {:as args}]
    (try
      (let [credentials (parse-ini-file "~/.aws/credentials")
            config (parse-ini-file "~/.aws/config")
            profiles (merge-aws-profiles credentials config)
            profile-list (mapv (fn [[name data]]
                                 {:name name
                                  :region (get data "region" "not set")
                                  :source (get data "_source" "unknown")})
                               (sort-by key profiles))]
        {:result {:profiles profile-list
                  :total (count profile-list)}})
      (catch Exception e
        (mulog/error ::list-profiles-failed :error (ex-message e))
        {:error (str "Failed to list AWS profiles: " (ex-message e))})))
  :output-schema [:map
                  [:result [:string {:desc "Map with :profiles (array of {:name :region :source}) and :total count"}]]
                  [:error {:optional true} [:string {:desc "Error message if failed"}]]])

(defcommand aws$whoami
  "Get current AWS caller identity via STS."
  (fn [& {:as args}]
    (try
      (let [profile (:profile args)
            cli-args (cond-> ["sts" "get-caller-identity" "--output" "json"]
                       profile (into ["--profile" profile]))
            {:keys [exit out err]} (run-aws-cli cli-args)]
        (if (zero? exit)
          (let [identity (json/read-str out :key-fn keyword)]
            {:result {:account (:Account identity)
                      :arn (:Arn identity)
                      :user-id (:UserId identity)
                      :profile (or profile "default")}})
          {:error (str "AWS STS call failed: " (str/trim err))}))
      (catch Exception e
        (mulog/error ::get-identity-failed :error (ex-message e))
        {:error (str "Failed to get AWS identity: " (ex-message e))})))
  :input-schema [:map
                 [:profile {:optional true} [:string {:desc "AWS profile name (optional, uses default if omitted)"}]]]
  :output-schema [:map
                  [:result {:optional true} [:string {:desc "Map with :account, :arn, :user-id, :profile"}]]
                  [:error {:optional true} [:string {:desc "Error message if STS call failed"}]]])

(defcommand aws$get-profile
  "Get details for a single AWS profile."
  (fn [& {:as args}]
    (let [profile-name (:profile-name args)]
      (if (str/blank? profile-name)
        {:error "profile-name is required"}
        (try
          (let [credentials (parse-ini-file "~/.aws/credentials")
                config (parse-ini-file "~/.aws/config")
                profiles (merge-aws-profiles credentials config)]
            (if-let [data (get profiles profile-name)]
              (let [detail (cond-> {:name profile-name
                                    :region (get data "region" "not set")
                                    :source (get data "_source" "unknown")}
                             (get data "aws_access_key_id")
                             (assoc :access-key-id (mask-value (get data "aws_access_key_id")))
                             (get data "aws_secret_access_key")
                             (assoc :secret-access-key (mask-value (get data "aws_secret_access_key")))
                             (get data "sso_start_url")
                             (assoc :sso-start-url (get data "sso_start_url"))
                             (get data "sso_account_id")
                             (assoc :sso-account-id (get data "sso_account_id"))
                             (get data "sso_role_name")
                             (assoc :sso-role-name (get data "sso_role_name"))
                             (get data "sso_region")
                             (assoc :sso-region (get data "sso_region"))
                             (get data "sso_session")
                             (assoc :sso-session (get data "sso_session"))
                             (get data "role_arn")
                             (assoc :role-arn (get data "role_arn"))
                             (get data "source_profile")
                             (assoc :source-profile (get data "source_profile"))
                             (get data "credential_process")
                             (assoc :credential-process (get data "credential_process")))]
                {:result detail})
              {:error (format "Profile '%s' not found" profile-name)}))
          (catch Exception e
            (mulog/error ::get-profile-failed :error (ex-message e))
            {:error (str "Failed to get profile details: " (ex-message e))})))))
  :input-schema [:map
                 [:profile-name [:string {:desc "Name of the AWS profile"}]]]
  :output-schema [:map
                  [:result {:optional true} [:string {:desc "Map with :name, :region, :source, and optional :access-key-id, :secret-access-key (masked), :sso-*, :role-arn, :source-profile, :credential-process"}]]
                  [:error {:optional true} [:string {:desc "Error message if profile not found or failed"}]]])

(defcommand aws$set-profile
  "Set active AWS profile and region for all aws-* MCP servers by updating their env vars"
  (fn [& {:as args}]
    (let [profile (:profile args)
          region (:region args)
          restart? (some-> (:restart args) str str/lower-case (= "true"))]
      (if (str/blank? profile)
        {:error "profile is required"}
        (try
          (let [updated (mcp-int/update-aws-env-vars! profile region)]
            (when (and restart? (seq updated))
              (doseq [server-name updated]
                (try
                  (mcp-int/reconnect-mcp-server! server-name)
                  (catch Exception e
                    (mulog/warn ::restart-after-profile-change-failed
                                :server server-name :error (ex-message e))))))
            {:result {:profile profile
                      :region (or region "unchanged")
                      :updated-servers (vec updated)
                      :restarted? (boolean (and restart? (seq updated)))}})
          (catch Exception e
            (mulog/error ::set-profile-failed :error (ex-message e))
            {:error (str "Failed to set AWS profile: " (ex-message e))})))))
  :input-schema [:map
                 [:profile [:string {:desc "AWS profile name to set"}]]
                 [:region {:optional true} [:string {:desc "AWS region override (optional)"}]]
                 [:restart {:optional true} [:string {:desc "Restart affected MCP servers after update (\"true\"/\"false\", default false)"}]]]
  :output-schema [:map
                  [:result {:optional true} [:string {:desc "Map with :profile, :region, :updated-servers (array), :restarted? (boolean)"}]]
                  [:error {:optional true} [:string {:desc "Error message if failed"}]]])

;; ============================================================================
;; Command Categories
;; ============================================================================

(def profile-discovery-commands
  "Commands for discovering AWS profiles and identity"
  [#'aws$list-profiles
   #'aws$whoami
   #'aws$get-profile])

(def profile-management-commands
  "Commands for switching AWS profiles"
  [#'aws$set-profile])

(def all-aws-commands
  "All AWS credential management commands available for tool use"
  (vec (concat profile-discovery-commands
               profile-management-commands)))
