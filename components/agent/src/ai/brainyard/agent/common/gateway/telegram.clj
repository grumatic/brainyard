;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.common.gateway.telegram
  "Telegram adapter for the messaging gateway (R3 — docs/design/hermes-comparison.md).

   A thin `Transport` over the Telegram Bot API (`getUpdates` long-poll +
   `sendMessage`) using the native HTTP client. The gateway core
   (`ai.brainyard.agent.common.gateway`) owns routing, pairing, and the loop;
   this namespace only moves bytes. Needs a bot token in `BY_TELEGRAM_TOKEN`.

   `gateway$start` / `gateway$stop` start and stop the in-process long-poll
   loop from a `by` session."
  (:require [ai.brainyard.agent.common.gateway :as gw]
            [ai.brainyard.agent.core.config :as config]
            [ai.brainyard.agent.core.tool :refer [defcommand]]
            [ai.brainyard.clj-http-native.interface :as http]
            [ai.brainyard.mulog.interface :as mulog]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(defn parse-updates
  "Project a parsed Telegram getUpdates response into gateway inbound messages
   {:platform :update-id :platform-user-id :chat-id :text}. Skips non-text and
   malformed updates."
  [body]
  (when (and (map? body) (get body "ok"))
    (vec
     (for [u (get body "result")
           :let [m (get u "message")]
           :when (and (map? m) (string? (get m "text"))
                      (get-in m ["from" "id"]) (get-in m ["chat" "id"]))]
       {:platform :telegram
        :update-id (get u "update_id")
        :platform-user-id (str "tg:" (get-in m ["from" "id"]))
        :chat-id (get-in m ["chat" "id"])
        :text (get m "text")}))))

(defrecord TelegramTransport [base offset]
  gw/Transport
  (poll [_]
    (let [url  (str base "/getUpdates?timeout=25&offset=" @offset)
          resp (http/get* url {:as :string :timeout-ms 30000 :throw-exceptions false})
          body (try (json/read-str (:body resp)) (catch Exception _ nil))
          msgs (parse-updates body)]
      (when (seq msgs)
        (reset! offset (inc (apply max (map :update-id msgs)))))
      (mapv #(dissoc % :update-id) msgs)))
  (send-reply! [_ chat-id text]
    (try
      (http/post (str base "/sendMessage")
                 {:body (json/write-str {:chat_id chat-id :text text})
                  :content-type :json :as :string :throw-exceptions false})
      (catch Exception e (mulog/warn ::send-failed :chat-id chat-id :exception e)))
    nil))

(defn telegram-transport
  "Build a Telegram transport from a bot token (default BY_TELEGRAM_TOKEN), or
   nil when no token is available."
  ([] (telegram-transport (System/getenv "BY_TELEGRAM_TOKEN")))
  ([token]
   (when-not (str/blank? token)
     (->TelegramTransport (str "https://api.telegram.org/bot" token) (atom 0)))))

;; ============================================================================
;; Start / stop commands
;; ============================================================================

(defcommand gateway$start
  "Start the Telegram messaging gateway long-poll loop in-process (needs BY_TELEGRAM_TOKEN)."
  (fn [& {:keys [token]}]
    (if-let [tp (telegram-transport (or (not-empty (str token)) (System/getenv "BY_TELEGRAM_TOKEN")))]
      (if (gw/start-gateway! (config/project-dir) tp)
        {:started true}
        {:error "gateway is already running"})
      {:error "no Telegram token — set BY_TELEGRAM_TOKEN or pass :token"}))
  :input-schema  [:map [:token {:optional true} [:string {:desc "Bot token (default BY_TELEGRAM_TOKEN)"}]]]
  :output-schema [:map
                  [:started {:optional true} [:boolean {:desc "True when the loop started"}]]
                  [:error   {:optional true} [:string {:desc "Error if no token / already running"}]]])

(defcommand gateway$stop
  "Stop the messaging gateway loop."
  (fn [& _] {:stopped (gw/stop-gateway!)})
  :input-schema  [:map]
  :output-schema [:map [:stopped [:boolean {:desc "True"}]]])

(def telegram-commands
  "Gateway start/stop commands (Telegram adapter)."
  [#'gateway$start #'gateway$stop])
