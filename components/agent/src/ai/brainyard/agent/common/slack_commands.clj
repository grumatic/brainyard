;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.agent.common.slack-commands
  "Slack integration commands for agents.
   Ported from cloudcast.backend.agent.common.slack-agent.

   Registers defcommands that agents can use to interact with Slack:
   - Send messages to users/channels
   - Look up users by email
   - List accessible channels
   - Verify bot token

   Requires SLACK_BOT_TOKEN environment variable or agent config."
  (:require [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]))

;; =====================================================
;; Token Resolution
;; =====================================================

(defn- get-bot-token
  "Resolve Slack bot token from agent config or environment."
  [& {:keys [agent-config]}]
  (or (get-in agent-config [:slack :bot-token])
      (System/getenv "SLACK_BOT_TOKEN")))

(defn- require-slack-api
  "Lazily require the slack API — soft dependency."
  []
  (try
    (require 'ai.brainyard.slack.interface)
    true
    (catch Exception _
      (mulog/warn ::slack-component-not-available :message "Slack component not available on classpath")
      false)))

(defn- slack-fn
  "Resolve a function from the slack interface namespace."
  [fn-name]
  (when (require-slack-api)
    (resolve (symbol "ai.brainyard.slack.interface" fn-name))))

;; =====================================================
;; Recipient Resolution
;; =====================================================

(defn- resolve-recipient
  "Resolve a recipient string to a Slack channel/user ID.
   Accepts: channel ID (C...), user ID (U...), email, or channel name."
  [token recipient]
  (cond
    ;; Already a channel/user ID
    (re-matches #"^[CU][A-Z0-9]+" recipient)
    {:type (if (.startsWith recipient "C") :channel :user)
     :id recipient}

    ;; Email address
    (re-find #"@" recipient)
    (let [lookup-fn (slack-fn "lookup-user-by-email")]
      (when lookup-fn
        (try
          (let [result (lookup-fn token recipient)]
            {:type :user
             :id (get-in result [:user :id])
             :display-name (get-in result [:user :real_name])})
          (catch Exception e
            {:error (str "User not found for email: " recipient)}))))

    ;; Channel name (strip # prefix)
    :else
    (let [name (if (.startsWith recipient "#") (subs recipient 1) recipient)
          list-fn (slack-fn "list-conversations")]
      (when list-fn
        (try
          (let [result (list-fn token)
                channels (:channels result)
                match (first (filter #(= (:name %) name) channels))]
            (if match
              {:type :channel :id (:id match) :display-name (:name match)}
              {:error (str "Channel not found: " name)}))
          (catch Exception e
            {:error (str "Failed to resolve channel: " (.getMessage e))}))))))

;; =====================================================
;; Commands
;; =====================================================

(defcommand slack-command$send-message
  "Send a Slack message; recipient may be channel ID (C...), user ID (U...), email, or #channel-name."
  (fn [& {:keys [recipient message thread-ts]}]
    (let [token (get-bot-token)]
      (if-not token
        {:error "SLACK_BOT_TOKEN not configured"}
        (let [resolved (resolve-recipient token recipient)]
          (if (:error resolved)
            resolved
            (let [post-fn (slack-fn "post-message")]
              (if-not post-fn
                {:error "Slack component not available"}
                (try
                  (let [result (post-fn token (:id resolved) message
                                        :thread-ts thread-ts)]
                    {:success true
                     :channel (:id resolved)
                     :ts (get-in result [:message :ts])
                     :recipient-type (:type resolved)
                     :display-name (:display-name resolved)})
                  (catch Exception e
                    {:error (str "Failed to send: " (.getMessage e))})))))))))
  :input-schema  [:map
                  [:recipient [:string {:desc "User ID, channel ID, email, or #channel-name"}]]
                  [:message   [:string {:desc "Message text (supports Slack mrkdwn)"}]]]
  :output-schema [:map
                  [:success [:boolean]]
                  [:channel [:string]]
                  [:ts [:string]]])

(defcommand slack-command$lookup-user
  "Look up a Slack user by email address."
  (fn [& {:keys [email]}]
    (let [token (get-bot-token)]
      (if-not token
        {:error "SLACK_BOT_TOKEN not configured"}
        (let [lookup-fn (slack-fn "lookup-user-by-email")]
          (if-not lookup-fn
            {:error "Slack component not available"}
            (try
              (let [result (lookup-fn token email)
                    user (:user result)]
                {:user-id (:id user)
                 :name (:real_name user)
                 :display-name (:display_name (:profile user))
                 :email email})
              (catch Exception e
                {:error (str "User not found: " (.getMessage e))})))))))
  :input-schema  [:map
                  [:email [:string {:desc "Email address to look up"}]]]
  :output-schema [:map
                  [:user-id [:string]]
                  [:name [:string]]])

(defcommand slack-command$list-channels
  "List accessible Slack channels."
  (fn [& {:keys [limit]}]
    (let [token (get-bot-token)]
      (if-not token
        {:error "SLACK_BOT_TOKEN not configured"}
        (let [list-fn (slack-fn "list-conversations")]
          (if-not list-fn
            {:error "Slack component not available"}
            (try
              (let [result (list-fn token :limit (or limit 50))
                    channels (->> (:channels result)
                                  (mapv (fn [ch]
                                          {:id (:id ch)
                                           :name (:name ch)
                                           :topic (get-in ch [:topic :value])
                                           :member-count (:num_members ch)
                                           :is-private (:is_private ch)})))]
                {:channels channels :count (count channels)})
              (catch Exception e
                {:error (str "Failed to list channels: " (.getMessage e))})))))))
  :input-schema  [:map]
  :output-schema [:map
                  [:channels [:vector [:map
                                       [:id :string]
                                       [:name :string]
                                       [:topic {:optional true} [:maybe :string]]
                                       [:member-count {:optional true} [:maybe :int]]
                                       [:is-private {:optional true} [:maybe :boolean]]]]]
                  [:count [:int]]])

(defcommand slack-command$verify-token
  "Verify Slack bot token and return bot info."
  (fn [& _]
    (let [token (get-bot-token)]
      (if-not token
        {:error "SLACK_BOT_TOKEN not configured"}
        (let [auth-fn (slack-fn "auth-test")]
          (if-not auth-fn
            {:error "Slack component not available"}
            (try
              (let [result (auth-fn token)]
                {:ok true
                 :bot-id (:bot_id result)
                 :team (:team result)
                 :user (:user result)})
              (catch Exception e
                {:error (str "Token verification failed: " (.getMessage e))})))))))
  :input-schema  [:map]
  :output-schema [:map
                  [:ok [:boolean]]
                  [:bot-id [:string]]
                  [:team [:string]]])
