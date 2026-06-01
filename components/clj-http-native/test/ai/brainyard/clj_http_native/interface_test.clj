;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: Apache-2.0
;; Licensed under the Apache License, Version 2.0 (the "License"); you may
;; not use this file except in compliance with the License. See LICENSE at
;; the repository root and CONTRIBUTING.md for contribution terms.

(ns ai.brainyard.clj-http-native.interface-test
  "Unit tests for the clj-http-native wrapper.

   We spin up an in-process `com.sun.net.httpserver.HttpServer` per
   fixture, so the tests run hermetically with no network access and
   no extra Maven deps. Each test gets its own server on a random
   port; the fixture binds `*base-url*` for the test body."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.clj-http-native.interface :as http])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]
           [java.io OutputStream]
           [java.nio.charset StandardCharsets]))

(def ^:dynamic *base-url* nil)
(def ^:dynamic *server*   nil)

(defn- read-body ^String [^HttpExchange exch]
  (let [in (.getRequestBody exch)]
    (slurp in)))

(defn- write-response
  ([^HttpExchange exch status ^String body]
   (write-response exch status body {}))
  ([^HttpExchange exch status ^String body headers]
   (let [bytes ^bytes (.getBytes (or body "") StandardCharsets/UTF_8)
         out-headers (.getResponseHeaders exch)]
     (doseq [[k v] headers] (.add out-headers (name k) (str v)))
     (.sendResponseHeaders exch (int status) (int (alength bytes)))
     (let [os ^OutputStream (.getResponseBody exch)]
       (.write os bytes)
       (.close os)))))

(defn- write-chunked
  "Write a chunked response that flushes after each chunk — simulates
   server-sent events. `body-fn` receives the OutputStream."
  [^HttpExchange exch headers body-fn]
  (let [out-headers (.getResponseHeaders exch)]
    (doseq [[k v] headers] (.add out-headers (name k) (str v)))
    (.sendResponseHeaders exch 200 0) ;; 0 = chunked
    (let [os ^OutputStream (.getResponseBody exch)]
      (try (body-fn os) (finally (.close os))))))

(defn- make-server
  "Build a one-handler HttpServer that dispatches based on `(.getRequestURI ex)`."
  ^HttpServer [handler-fn]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        h (reify HttpHandler
            (handle [_ exch] (handler-fn exch)))]
    (.createContext server "/" h)
    (.setExecutor server nil)
    (.start server)
    server))

(defn- with-server [handler-fn f]
  (let [server (make-server handler-fn)
        port   (.getPort (.getAddress server))]
    (binding [*server* server
              *base-url* (str "http://127.0.0.1:" port)]
      (try (f) (finally (.stop server 0))))))

;; ============================================================================
;; Tests
;; ============================================================================

(deftest get-returns-status-headers-body
  (with-server
    (fn [exch]
      (write-response exch 200 "hello world" {"X-Custom" "yes"}))
    (fn []
      (let [r (http/get *base-url* {:as :string})]
        (is (= 200 (:status r)))
        (is (= "hello world" (:body r)))
        (is (= "yes" (get-in r [:headers "x-custom"])))
        (testing "headers map uses lowercase keys"
          (is (every? #(= (clojure.string/lower-case %) %)
                      (keys (:headers r)))))))))

(deftest post-with-body-and-headers
  (let [seen-method   (atom nil)
        seen-body     (atom nil)
        seen-content  (atom nil)]
    (with-server
      (fn [exch]
        (reset! seen-method (.getRequestMethod exch))
        (reset! seen-body (read-body exch))
        (reset! seen-content (.getFirst (.getRequestHeaders exch) "Content-Type"))
        (write-response exch 201 "{\"ok\":true}"))
      (fn []
        (let [r (http/post *base-url*
                           {:body "{\"x\":1}"
                            :content-type :json
                            :as :string})]
          (is (= 201 (:status r)))
          (is (= "{\"ok\":true}" (:body r)))
          (is (= "POST" @seen-method))
          (is (= "{\"x\":1}" @seen-body))
          (is (= "application/json" @seen-content)))))))

(deftest throws-on-non-2xx-when-requested
  (with-server
    (fn [exch] (write-response exch 500 "boom"))
    (fn []
      (testing "throw-exceptions true → throws ex-info"
        (let [e (try (http/get *base-url*
                               {:as :string :throw-exceptions true})
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (instance? clojure.lang.ExceptionInfo e))
          (is (= 500 (:status (ex-data e))))
          (is (= "boom" (:body (ex-data e))))))
      (testing "throw-exceptions false → returns response"
        (let [r (http/get *base-url*
                          {:as :string :throw-exceptions false})]
          (is (= 500 (:status r)))
          (is (= "boom" (:body r))))))))

(deftest retry-after-header-survives-into-ex-data
  ;; matches llm.clj retry-with-backoff which reads
  ;; (get-in (ex-data e) [:headers "retry-after"])
  (with-server
    (fn [exch]
      (write-response exch 429 "slow down" {"Retry-After" "5"}))
    (fn []
      (let [e (try (http/get *base-url*
                             {:as :string :throw-exceptions true})
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= 429 (:status (ex-data e))))
        (is (= "5"  (get-in (ex-data e) [:headers "retry-after"])))))))

(deftest reader-body-is-buffered-reader-and-streams
  ;; mirrors llm.clj's :as :reader path that feeds sse/process-*-stream
  (with-server
    (fn [exch]
      (write-chunked exch {"Content-Type" "text/event-stream"}
                     (fn [^OutputStream os]
                       (doseq [line ["event: a\n" "data: 1\n" "\n"
                                     "event: b\n" "data: 2\n" "\n"]]
                         (.write os (.getBytes ^String line StandardCharsets/UTF_8))
                         (.flush os)))))
    (fn []
      (let [r (http/get *base-url* {:as :reader})]
        (is (= 200 (:status r)))
        (is (instance? java.io.BufferedReader (:body r)))
        (let [lines (with-open [rdr ^java.io.BufferedReader (:body r)]
                      (doall (line-seq rdr)))]
          ;; Six raw lines from the SSE stream (line-seq drops the trailing \n).
          (is (= 6 (count lines)))
          (is (= "event: a" (first lines))))))))

(deftest stream-body-is-inputstream
  (with-server
    (fn [exch] (write-response exch 200 "raw bytes here"))
    (fn []
      (let [r (http/get *base-url* {:as :stream})]
        (is (instance? java.io.InputStream (:body r)))
        (is (= "raw bytes here"
               (with-open [is ^java.io.InputStream (:body r)]
                 (slurp is))))))))

(deftest delete-sends-correct-method
  (let [seen (atom nil)]
    (with-server
      (fn [exch]
        (reset! seen (.getRequestMethod exch))
        (write-response exch 204 ""))
      (fn []
        (let [r (http/delete *base-url* {:as :string})]
          (is (= 204 (:status r)))
          (is (= "DELETE" @seen)))))))

(deftest unknown-options-do-not-error
  ;; migration ergonomics: existing call sites pass :connection-manager,
  ;; :socket-timeout, etc. The wrapper must silently accept and ignore
  ;; them.
  (with-server
    (fn [exch] (write-response exch 200 "ok"))
    (fn []
      (is (= 200
             (:status
              (http/post *base-url*
                         {:body "x"
                          :as :string
                          :connection-manager :anything
                          :socket-timeout 30000
                          :insecure? false
                          :random-unknown-key 42})))))))
