;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.clj-llm.sse-request-test
  "SPIKE §18 slice 3: a REAL HTTP exchange, over a loopback socket.

   Slices 1 and 2 used a StringReader and a fake publisher. This is the first
   one where a socket is involved, and it is where the claim finally gets
   tested rather than reasoned about: **cancelling the Flow aborts the
   exchange**, so a streaming body needs no `.close` to escape.

   The server writes SSE slowly and records whether the client went away, so
   'the flow stopped' and 'the connection actually dropped' are distinguished —
   the first can happen without the second, and only the second retires
   `:active-http`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.brainyard.clj-llm.core.sse :as sse]
            [ai.brainyard.clj-llm.core.sse-publisher :as sse-pub]
            [missionary.core :as m])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.io BufferedReader StringReader]
           [java.net InetSocketAddress URI]
           [java.net.http HttpClient HttpRequest]
           [java.nio.charset StandardCharsets]))

(defn- start-server!
  "An SSE server writing `chunks` with `delay-ms` between them. Sets
   `:client-gone?` when a write fails — i.e. the peer closed the connection."
  [chunks delay-ms !state]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/sse"
     (reify HttpHandler
       (handle [_ ex]
         (try
           (.set (.getResponseHeaders ^HttpExchange ex) "Content-Type" "text/event-stream")
           (.sendResponseHeaders ^HttpExchange ex 200 0)
           (with-open [os (.getResponseBody ^HttpExchange ex)]
             (doseq [^String c chunks]
               (.write os (.getBytes c StandardCharsets/UTF_8))
               (.flush os)
               (swap! !state update :written inc)
               (when (pos? delay-ms) (Thread/sleep (long delay-ms)))))
           (catch Throwable _
             ;; A failed write means the client hung up.
             (swap! !state assoc :client-gone? true))
           (finally (.close ^HttpExchange ex))))))
    (.setExecutor server nil)
    (.start server)
    server))

(defn- url-for [^HttpServer s] (str "http://127.0.0.1:" (.getPort (.getAddress s)) "/sse"))

(defn- get-req [^String url]
  (-> (HttpRequest/newBuilder (URI/create url)) (.GET) (.build)))

(defmacro with-server [[sym chunks delay-ms !state] & body]
  `(let [~sym (start-server! ~chunks ~delay-ms ~!state)]
     (try ~@body (finally (.stop ~(vary-meta sym assoc :tag `HttpServer) 0)))))

;; ============================================================================

(deftest real-exchange-yields-the-same-events-as-the-blocking-reader
  (testing "over a socket, the push path agrees with sse/read-sse-events"
    (let [body   (str "event: message_start\ndata: {\"a\":1}\n\n"
                      "data: {\"b\":2}\n\n"
                      "data: [DONE]\n\n")
          !s     (atom {:written 0 :client-gone? false})
          expect (doall (sse/read-sse-events (BufferedReader. (StringReader. body))))]
      (with-server [srv [body] 0 !s]
        (let [client (HttpClient/newHttpClient)
              p (promise)]
          ((m/reduce conj [] (sse-pub/request-event-flow client (get-req (url-for srv))))
           #(deliver p {:ok %}) #(deliver p {:err %}))
          (is (= {:ok expect} (deref p 15000 :TIMEOUT))))))))

(deftest cancelling-the-flow-aborts-the-real-exchange
  (testing "THE CLAIM, over a socket

           Every earlier section said a streaming body could only be escaped by
           closing the stream. Here the flow is cancelled and the SERVER
           notices the client is gone — nothing called .close."
    (let [chunks (vec (for [i (range 400)] (str "data: " i "\n\n")))
          !s     (atom {:written 0 :client-gone? false})]
      (with-server [srv chunks 20 !s]
        (let [client (HttpClient/newHttpClient)
              p (promise)
              cancel ((m/reduce conj [] (sse-pub/request-event-flow client (get-req (url-for srv))))
                      #(deliver p {:ok %}) #(deliver p {:err %}))]
          (Thread/sleep 500)
          (is (pos? (:written @!s)) "the server should be mid-stream")
          (is (false? (:client-gone? @!s)) "and the client still attached")

          (cancel)
          (is (not= :TIMEOUT (deref p 5000 :TIMEOUT)) "cancel must settle the flow")

          ;; The server only learns the peer is gone on its next write, so give
          ;; it a few write attempts.
          (let [gone? (loop [n 0]
                        (cond (:client-gone? @!s) true
                              (> n 60) false
                              :else (do (Thread/sleep 50) (recur (inc n)))))]
            (is gone?
                "the server must have seen the connection drop — this is what
                 makes .close on :active-http unnecessary for streaming"))
          (is (< (:written @!s) (count chunks))
              "and it stopped early rather than writing the whole stream"))))))

(deftest server-closing-early-completes-the-flow
  (testing "a truncated stream completes rather than hanging"
    (let [!s (atom {:written 0 :client-gone? false})]
      (with-server [srv ["data: one\n\n" "data: tw"] 0 !s]
        (let [client (HttpClient/newHttpClient)
              p (promise)]
          ((m/reduce conj [] (sse-pub/request-event-flow client (get-req (url-for srv))))
           #(deliver p {:ok %}) #(deliver p {:err %}))
          ;; "data: tw" never gets its blank line, so EOF flushes it — exactly
          ;; what read-sse-events does at EOF.
          (is (= {:ok [{:event nil :data "one"} {:event nil :data "tw"}]}
                 (deref p 15000 :TIMEOUT))))))))
