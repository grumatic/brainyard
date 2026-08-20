;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

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

(deftest headers-past-eight-survive
  ;; Regression: headers-from-response built a transient with `assoc!` but
  ;; discarded its return, relying on in-place mutation. Past 8 entries the
  ;; transient promotes to a hash-map and `assoc!` returns a NEW object, so the
  ;; 9th+ key was silently lost. With the JDK's case-insensitive header order
  ;; that dropped the alphabetically-last header — e.g. `www-authenticate` on a
  ;; 401 from a server with many headers (broke OAuth 401-challenge discovery).
  (with-server
    (fn [exch]
      (write-response exch 401 "no"
                      {"A-One" "1" "B-Two" "2" "C-Three" "3" "D-Four" "4"
                       "E-Five" "5" "F-Six" "6" "G-Seven" "7" "H-Eight" "8"
                       "WWW-Authenticate" "Bearer realm=\"x\"" "Z-Last" "zz"}))
    (fn []
      (let [h (:headers (http/get *base-url* {:as :string :throw-exceptions false}))]
        (testing "every header survives, including the alphabetically-last ones"
          (doseq [[k v] {"a-one" "1" "h-eight" "8"
                         "www-authenticate" "Bearer realm=\"x\"" "z-last" "zz"}]
            (is (= v (get h k)) (str k " present and correct"))))))))

(deftest content-type-sugar-sets-the-header
  ;; Regression: only :content-type :json was honored; :form and string content
  ;; types were silently dropped — so strict servers (OAuth /token) rejected the
  ;; request ("Content-Type must be application/x-www-form-urlencoded").
  (let [seen (atom nil)]
    (with-server
      (fn [exch]
        (reset! seen (.getFirst (.getRequestHeaders exch) "Content-Type"))
        (write-response exch 200 "ok"))
      (fn []
        (testing ":form keyword → application/x-www-form-urlencoded"
          (http/post *base-url* {:body "a=b" :content-type :form :as :string})
          (is (= "application/x-www-form-urlencoded" @seen)))
        (testing ":json keyword → application/json"
          (http/post *base-url* {:body "{}" :content-type :json :as :string})
          (is (= "application/json" @seen)))
        (testing "a string content type is used verbatim"
          (http/post *base-url* {:body "x" :content-type "text/plain; charset=utf-8" :as :string})
          (is (= "text/plain; charset=utf-8" @seen)))))))

;; ============================================================================
;; Error bodies are REALIZED, whatever `:as` asked for
;;
;; Regression: the error branch coerced an InputStream but let anything else
;; fall through, so `:as :reader` (the streaming chat path) handed back an
;; unread BufferedReader. Callers inspect an error body to tell one failure
;; from another — clj-llm reads a 429 to distinguish an exhausted quota from a
;; throttle — and that check silently failed on exactly the streaming calls,
;; costing ~63s of pointless backoff per call.
;;
;; A body cannot be read later: the connection may be closed by then, and
;; whoever reads first consumes it for everyone. So an error body is realized
;; eagerly, and these tests pin that for every `:as` the codebase uses.
;; ============================================================================

(def ^:private quota-429-body
  "{\"error\":{\"message\":\"You have no credits remaining.\",\"type\":\"insufficient_quota\",\"code\":\"credit_balance_exhausted\"}}")

(deftest error-body-is-realized-for-every-as
  (doseq [as [:string :reader :stream]]
    (testing (str "429 error body is a readable String with :as " as)
      (with-server
        (fn [exch] (write-response exch 429 quota-429-body))
        (fn []
          (let [e (try (http/get *base-url* {:as as :throw-exceptions true})
                       (catch clojure.lang.ExceptionInfo e e))
                body (:body (ex-data e))]
            (is (= 429 (:status (ex-data e))))
            (is (string? body)
                (str ":as " as " must yield a realized String error body, not a stream"))
            ;; The substring a caller actually greps for.
            (is (clojure.string/includes? body "insufficient_quota"))))))))

(deftest success-body-still-streams-for-reader
  ;; The realization above is scoped to the ERROR branch — a 2xx :as :reader
  ;; must still hand back a Reader, or streaming chat would break.
  (with-server
    (fn [exch] (write-response exch 200 "data: one\n\ndata: two\n\n"))
    (fn []
      (let [r (http/get *base-url* {:as :reader :throw-exceptions true})]
        (is (= 200 (:status r)))
        (is (instance? java.io.Reader (:body r))
            "a successful :as :reader response must stay lazy")))))
