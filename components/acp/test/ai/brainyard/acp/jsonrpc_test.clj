;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.acp.jsonrpc-test
  "Unit tests for JSON-RPC 2.0 message construction, encoding, and
   classification. Pure data — no I/O."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [ai.brainyard.acp.core.jsonrpc :as jsonrpc]))

(deftest id-source-test
  (testing "make-id-source yields monotonically increasing ids"
    (let [next-id (jsonrpc/make-id-source)]
      (is (= 1 (next-id)))
      (is (= 2 (next-id)))
      (is (= 3 (next-id)))))

  (testing "separate id sources are independent"
    (let [a (jsonrpc/make-id-source)
          b (jsonrpc/make-id-source)]
      (a) (a) (a)
      (is (= 1 (b))))))

(deftest request-construction-test
  (testing "request includes jsonrpc version, id, method, params"
    (let [req (jsonrpc/request 7 "session/prompt" {:sessionId "s1"})]
      (is (= "2.0" (:jsonrpc req)))
      (is (= 7 (:id req)))
      (is (= "session/prompt" (:method req)))
      (is (= {:sessionId "s1"} (:params req)))))

  (testing "params may be omitted with nil"
    (let [req (jsonrpc/request 1 "ping" nil)]
      (is (not (contains? req :params)))))

  (testing "non-string method throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (jsonrpc/request 1 :ping nil)))))

(deftest notification-construction-test
  (testing "notification has no id, has method"
    (let [n (jsonrpc/notification "session/update" {:sessionId "s1"})]
      (is (= "2.0" (:jsonrpc n)))
      (is (not (contains? n :id)))
      (is (= "session/update" (:method n)))
      (is (= {:sessionId "s1"} (:params n)))))

  (testing "notification with nil params omits the key"
    (let [n (jsonrpc/notification "ping" nil)]
      (is (not (contains? n :params))))))

(deftest response-construction-test
  (testing "success response carries id and result"
    (let [r (jsonrpc/response 42 {:ok true})]
      (is (= 42 (:id r)))
      (is (= {:ok true} (:result r)))
      (is (not (contains? r :error)))))

  (testing "error response with code+message+data"
    (let [r (jsonrpc/error-response 42 -32601 "Method not found" {:method "bogus"})]
      (is (= 42 (:id r)))
      (is (= -32601 (-> r :error :code)))
      (is (= "Method not found" (-> r :error :message)))
      (is (= {:method "bogus"} (-> r :error :data)))))

  (testing "error response without data omits data key"
    (let [r (jsonrpc/error-response 42 -32601 "Method not found")]
      (is (not (contains? (:error r) :data))))))

(deftest encode-decode-roundtrip-test
  (testing "request roundtrips"
    (let [req (jsonrpc/request 1 "initialize" {:protocolVersion 1})
          line (jsonrpc/encode req)
          parsed (jsonrpc/decode line)]
      (is (= req parsed))))

  (testing "notification roundtrips"
    (let [n (jsonrpc/notification "session/update"
                                  {:sessionId "s1"
                                   :sessionUpdate "agent_message_chunk"
                                   :content {:type "text" :text "hi"}})
          parsed (jsonrpc/decode (jsonrpc/encode n))]
      (is (= n parsed))))

  (testing "encoded line is a valid JSON object string"
    (let [req (jsonrpc/request 1 "ping" nil)
          line (jsonrpc/encode req)]
      (is (string? line))
      (is (= req (json/read-str line :key-fn keyword))))))

(deftest decode-malformed-test
  (testing "malformed JSON throws ExceptionInfo with :type :parse-error"
    (try
      (jsonrpc/decode "not valid json {")
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :parse-error (:type (ex-data e))))
        (is (= "not valid json {" (:line (ex-data e))))))))

(deftest classify-test
  (testing "request: has both method and id"
    (is (= :request
           (jsonrpc/classify {:jsonrpc "2.0" :id 1 :method "ping"}))))

  (testing "notification: has method, no id"
    (is (= :notification
           (jsonrpc/classify {:jsonrpc "2.0" :method "session/update"
                              :params {:sessionId "s1"}}))))

  (testing "success response: id + result, no method"
    (is (= :response
           (jsonrpc/classify {:jsonrpc "2.0" :id 1 :result {}}))))

  (testing "error response: id + error, no method"
    (is (= :response
           (jsonrpc/classify {:jsonrpc "2.0" :id 1
                              :error {:code -32601 :message "x"}}))))

  (testing "invalid: bare envelope"
    (is (= :invalid
           (jsonrpc/classify {:jsonrpc "2.0"}))))

  (testing "invalid: not a map"
    (is (nil? (jsonrpc/classify "string"))))

  (testing "predicate helpers"
    (let [req {:jsonrpc "2.0" :id 1 :method "ping"}
          notif {:jsonrpc "2.0" :method "session/update"}
          ok-resp {:jsonrpc "2.0" :id 1 :result {}}
          err-resp {:jsonrpc "2.0" :id 1 :error {:code -32601 :message "x"}}]
      (is (jsonrpc/request? req))
      (is (jsonrpc/notification? notif))
      (is (jsonrpc/response? ok-resp))
      (is (jsonrpc/response? err-resp))
      (is (jsonrpc/error? err-resp))
      (is (not (jsonrpc/error? ok-resp))))))
