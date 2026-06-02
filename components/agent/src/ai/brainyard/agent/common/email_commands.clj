;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.email-commands
  "Email commands for agents via AWS SES.
   Ported from cloudcast.backend.agent.common.email-agent.

   Registers defcommands for sending emails, verifying config, and listing templates.
   Uses the email Polylith component (soft dependency)."
  (:require [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.string :as str]))

;; =====================================================
;; Email Component (Soft Dependency)
;; =====================================================

(defn- require-email []
  (try
    (require 'ai.brainyard.email.interface)
    true
    (catch Exception _
      (mulog/warn ::email-component-not-available :message "Email component not available on classpath")
      false)))

(defn- email-fn [fn-name]
  (when (require-email)
    (resolve (symbol "ai.brainyard.email.interface" fn-name))))

(defn- get-ses-client []
  (when-let [create-fn (email-fn "create-client")]
    (create-fn (or (System/getenv "AWS_REGION") "us-east-1"))))

(defn- get-sender []
  (let [email (or (System/getenv "MAIL_SENDER_EMAIL")
                  (System/getenv "EMAIL_SENDER")
                  (System/getenv "SES_SENDER_EMAIL")
                  "noreply@example.com")
        name  (System/getenv "MAIL_SENDER_NAME")]
    (if name
      (str name " <" email ">")
      email)))

(defn- get-reply-to []
  (System/getenv "MAIL_REPLY_TO"))

;; =====================================================
;; Email Validation
;; =====================================================

(def ^:private email-pattern
  #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$")

(defn- valid-email? [email]
  (and (string? email)
       (re-matches email-pattern email)))

;; =====================================================
;; Commands
;; =====================================================

(defcommand email-command$send-email
  "Send an email via AWS SES. Supports plain text and HTML body."
  (fn [& {:keys [to subject body cc bcc html]}]
    (let [client (get-ses-client)
          sender (get-sender)]
      (cond
        (not client)
        {:error "Email component or AWS SES not configured"}

        (not (valid-email? to))
        {:error (str "Invalid email address: " to)}

        (str/blank? subject)
        {:error "Subject is required"}

        :else
        (let [send-fn (email-fn "send-email")
              reply-to (get-reply-to)
              body-content (if html
                             {:html html :text (or body "")}
                             (or body ""))]
          (try
            (let [result (send-fn client sender to subject body-content
                                  :cc (when cc [cc])
                                  :bcc (when bcc [bcc])
                                  :reply-to (when reply-to [reply-to]))]
              {:success true
               :message-id (:message-id result)
               :to to
               :subject subject})
            (catch Exception e
              {:error (str "Failed to send email: " (.getMessage e))}))))))
  :input-schema [:map
                 [:to [:string {:desc "Recipient email address"}]]
                 [:subject [:string {:desc "Email subject"}]]
                 [:body [:string {:desc "Email body (plain text)"}]]]
  :output-schema [:map
                  [:success :boolean]
                  [:message-id {:optional true} :string]])

(defcommand email-command$send-notification
  "Send a notification email with a pre-formatted template."
  (fn [& {:keys [to subject title message severity]}]
    (let [client (get-ses-client)
          sender (get-sender)
          severity (or severity "info")
          html (str "<div style='font-family:sans-serif;max-width:600px;margin:auto;'>"
                    "<div style='padding:16px;background:" (case severity
                                                             "error" "#fee2e2"
                                                             "warning" "#fef3c7"
                                                             "#dbeafe") ";'>"
                    "<h2>" (or title subject) "</h2>"
                    "</div>"
                    "<div style='padding:16px;'>"
                    "<p>" message "</p>"
                    "</div></div>")]
      (if-not client
        {:error "Email component not configured"}
        (let [send-fn (email-fn "send-email")]
          (try
            (let [result (send-fn client sender to subject {:html html :text message})]
              {:success true :message-id (:message-id result)})
            (catch Exception e
              {:error (str "Failed to send notification: " (.getMessage e))}))))))
  :input-schema [:map
                 [:to [:string {:desc "Recipient email"}]]
                 [:subject [:string {:desc "Email subject"}]]
                 [:message [:string {:desc "Notification message"}]]
                 [:severity [:string {:desc "info, warning, or error"}]]]
  :output-schema [:map
                  [:success :boolean]])

(defcommand email-command$verify-config
  "Verify email configuration (AWS SES client, sender identity)."
  (fn [& _]
    (let [client (get-ses-client)
          sender (get-sender)]
      (if-not client
        {:configured false :error "AWS SES client not available"}
        (let [list-fn (email-fn "list-email-identities")]
          (try
            (let [identities (list-fn client)
                  verified (some #(= (:identity-name %) sender)
                                 (:email-identities identities))]
              {:configured true
               :sender sender
               :sender-verified (boolean verified)
               :region (or (System/getenv "AWS_REGION") "us-east-1")})
            (catch Exception e
              {:configured false :error (str "SES check failed: " (.getMessage e))}))))))
  :input-schema [:map]
  :output-schema [:map
                  [:configured :boolean]
                  [:sender {:optional true} :string]])

(defcommand email-command$list-templates
  "List available SES email templates."
  (fn [& _]
    (let [client (get-ses-client)]
      (if-not client
        {:error "AWS SES client not available"}
        (let [list-fn (email-fn "list-templates")]
          (try
            (let [result (list-fn client)]
              {:templates (->> (:templates-metadata result)
                               (mapv :template-name))
               :count (count (:templates-metadata result))})
            (catch Exception e
              {:error (str "Failed to list templates: " (.getMessage e))}))))))
  :input-schema [:map]
  :output-schema [:map
                  [:templates {:optional true} [:vector :string]]
                  [:count {:optional true} :int]])
