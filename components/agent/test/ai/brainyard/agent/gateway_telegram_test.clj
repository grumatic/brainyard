;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.gateway-telegram-test
  "Tests for the Telegram gateway adapter (R3): getUpdates parsing, and the
   TelegramTransport poll/send-reply over a stubbed HTTP client."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.brainyard.agent.common.gateway :as gw]
            [ai.brainyard.agent.common.gateway.telegram :as tg]
            [ai.brainyard.clj-http-native.interface :as http]
            [clojure.data.json :as json]))

(def sample-updates
  {"ok" true
   "result" [{"update_id" 100
              "message" {"message_id" 1
                         "from" {"id" 42} "chat" {"id" 555}
                         "text" "hello bot"}}
             {"update_id" 101
              "message" {"message_id" 2 "from" {"id" 42} "chat" {"id" 555}}} ;; no text → skip
             {"update_id" 102
              "message" {"message_id" 3
                         "from" {"id" 7} "chat" {"id" 9}
                         "text" "second"}}]})

(deftest parse-updates-projects-text-messages
  (let [msgs (tg/parse-updates sample-updates)]
    (is (= 2 (count msgs)) "non-text update skipped")
    (is (= {:platform :telegram :update-id 100 :platform-user-id "tg:42"
            :chat-id 555 :text "hello bot"}
           (first msgs)))
    (is (= "tg:7" (:platform-user-id (second msgs)))))
  (testing "guards"
    (is (= [] (tg/parse-updates {"ok" true "result" []})))
    (is (nil? (tg/parse-updates {"ok" false})))
    (is (nil? (tg/parse-updates nil)))))

(deftest transport-poll-parses-and-advances-offset
  (let [tp (tg/telegram-transport "FAKE-TOKEN")]
    (with-redefs [http/get* (fn [_url _opts] {:status 200 :body (json/write-str sample-updates)})]
      (let [msgs (gw/poll tp)]
        (is (= 2 (count msgs)))
        (is (= "hello bot" (:text (first msgs))))
        (is (not (contains? (first msgs) :update-id)) "update-id stripped from delivered msg")
        (is (= 103 @(:offset tp)) "offset advanced to max update_id + 1")))
    (testing "empty result leaves offset unchanged"
      (with-redefs [http/get* (fn [_ _] {:status 200 :body (json/write-str {"ok" true "result" []})})]
        (is (= [] (gw/poll tp)))
        (is (= 103 @(:offset tp)))))))

(deftest transport-send-posts-sendmessage
  (let [tp (tg/telegram-transport "FAKE-TOKEN")
        captured (atom nil)]
    (with-redefs [http/post (fn [url opts] (reset! captured {:url url :opts opts}) {:status 200 :body "{}"})]
      (gw/send-reply! tp 555 "hi there")
      (is (re-find #"/sendMessage$" (:url @captured)))
      (let [payload (json/read-str (get-in @captured [:opts :body]))]
        (is (= 555 (get payload "chat_id")))
        (is (= "hi there" (get payload "text")))))))

(deftest telegram-transport-requires-token
  (is (nil? (tg/telegram-transport "")))
  (is (nil? (tg/telegram-transport "   ")))
  (is (some? (tg/telegram-transport "abc"))))
