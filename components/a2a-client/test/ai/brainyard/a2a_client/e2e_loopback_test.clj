;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.a2a-client.e2e-loopback-test
  "End-to-end tests against a real in-process HTTP server on an ephemeral
   loopback port.

   Everything else in this component's suite is pure. This namespace is
   the one that proves the parts that only fail over a socket: the SSE
   reader thread, `stop!`, EOF handling, HTTP error mapping, and the
   discovery round trip.

   The stub uses `com.sun.net.httpserver` — the same JDK listener Phase 5
   builds the real server on — so this doubles as an early proving ground
   for that choice."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.brainyard.a2a.interface :as a2a]
            [ai.brainyard.a2a-client.core.client :as client]
            [ai.brainyard.a2a-client.core.discovery :as discovery]
            [ai.brainyard.a2a-client.core.transport :as transport])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

;; =============================================================================
;; Stub server
;; =============================================================================

(defn- respond!
  [^HttpExchange ex status ^String body & {:keys [content-type]}]
  (let [bs (.getBytes body StandardCharsets/UTF_8)]
    (.set (.getResponseHeaders ex) "Content-Type" (or content-type "application/json"))
    (.sendResponseHeaders ex status (alength bs))
    (with-open [os (.getResponseBody ex)]
      (.write os bs))))

(defn- read-body ^String [^HttpExchange ex]
  (slurp (.getRequestBody ex) :encoding "UTF-8"))

(defn- handler ^HttpHandler [f]
  (reify HttpHandler
    (handle [_ ex]
      (try (f ex)
           (catch Throwable t
             (try (respond! ex 500 (str "{\"stub-error\":\"" (ex-message t) "\"}"))
                  (catch Throwable _ nil)))
           (finally (.close ^HttpExchange ex))))))

(defn- start-stub!
  "Start a stub A2A server. `routes` is {path (fn [exchange])}.
   Returns {:server s :base-url u :port n}."
  [routes]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    ;; create/0 with port 0 binds immediately, so the real port is known
    ;; BEFORE start — which is what lets a route close over its own
    ;; base-url (the card has to advertise the URL it is served from).
    (let [port (.getPort (.getAddress server))
          stub {:server server
                :port   port
                :base-url (str "http://127.0.0.1:" port)}
          routes (if (fn? routes) (routes stub) routes)]
      (doseq [[path f] routes]
        (.createContext server ^String path (handler f)))
      (.setExecutor server nil)
      (.start server)
      stub)))

(defn- stop-stub! [{:keys [server]}]
  (.stop ^HttpServer server 0))

(defmacro with-stub
  "Run `body` with `binding` bound to a started stub server.

   `routes` is either a `{path handler-fn}` map, or a FUNCTION of the stub
   returning one — the function form is how a route reaches its own
   `:base-url`, which an Agent Card must advertise."
  [[binding routes] & body]
  `(let [~binding (start-stub! ~routes)]
     (try ~@body (finally (stop-stub! ~binding)))))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn- card-for [base-url & {:keys [streaming]}]
  {:name "stub-peer"
   :url (str base-url "/a2a")
   :protocolVersion a2a/PROTOCOL_VERSION
   :capabilities {:streaming (boolean streaming)}
   :skills [{:id "echo" :name "Echo"}]})

(defn- jsonrpc-result [id result]
  (a2a/encode (a2a/response id result)))

(defn- sse-frame [id result]
  (str "data: " (jsonrpc-result id result) "\n\n"))

;; =============================================================================
;; Discovery
;; =============================================================================

(deftest discovery-round-trip-test
  (with-stub [s (fn [stub]
                  {a2a/AGENT_CARD_PATH
                   (fn [ex] (respond! ex 200 (a2a/encode (card-for (:base-url stub)))))})]
    (discovery/invalidate!)
    (testing "the well-known card is fetched, validated and cached"
      (let [{:keys [card error]} (discovery/fetch-card! (:base-url s))]
        (is (nil? error))
        (is (= "stub-peer" (:name card)))
        (is (= ["echo"] (mapv :id (a2a/card-skills card))))))

    (testing "a second fetch is served from cache"
      (is (:cached (discovery/fetch-card! (:base-url s)))))

    (testing ":refresh? bypasses the cache"
      (is (not (:cached (discovery/fetch-card! (:base-url s) :refresh? true)))))

    (discovery/invalidate!)))

(deftest discovery-error-paths-test
  (testing "a 404 on the well-known path is an ordinary error"
    (with-stub [s {a2a/AGENT_CARD_PATH (fn [ex] (respond! ex 404 "{}"))}]
      (discovery/invalidate!)
      (let [{:keys [error]} (discovery/fetch-card! (:base-url s))]
        (is (some? error))
        (is (str/includes? error "404")))))

  (testing "malformed JSON is an error, not an exception"
    (with-stub [s {a2a/AGENT_CARD_PATH (fn [ex] (respond! ex 200 "not json{{"))}]
      (discovery/invalidate!)
      (is (some? (:error (discovery/fetch-card! (:base-url s)))))))

  (testing "a card failing validation is rejected"
    (with-stub [s {a2a/AGENT_CARD_PATH (fn [ex] (respond! ex 200 "{\"nope\":1}"))}]
      (discovery/invalidate!)
      (is (some? (:error (discovery/fetch-card! (:base-url s)))))))

  (testing "an unreachable host is an ordinary error, not a throw"
    (discovery/invalidate!)
    (let [{:keys [error]} (discovery/fetch-card! "http://127.0.0.1:1" :timeout-ms 2000)]
      (is (some? error))))

  (discovery/invalidate!))

;; =============================================================================
;; Blocking RPC
;; =============================================================================

(deftest rpc-round-trip-test
  (let [!seen (atom nil)]
    (with-stub [s {"/a2a" (fn [ex]
                            (reset! !seen (a2a/decode (read-body ex)))
                            (respond! ex 200
                                      (jsonrpc-result
                                       1 {:id "t-1" :kind "task"
                                          :contextId "c-1"
                                          :status {:state "completed"
                                                   :message {:messageId "m" :role "agent"
                                                             :parts [{:kind "text"
                                                                      :text "pong"}]}}})))}]
      (let [peer (client/make-peer {:name "stub" :url (str (:base-url s) "/a2a")
                                    :card (card-for (:base-url s))})
            out  (client/send-message! peer "ping")]

        (testing "the answer round-trips"
          (is (nil? (:error out)))
          (is (= "pong" (:answer out)))
          (is (= "t-1" (:task-id out)))
          (is (= :completed (:state out))))

        (testing "we sent a well-formed JSON-RPC request"
          (is (= "2.0" (:jsonrpc @!seen)))
          (is (= "message/send" (:method @!seen)))
          (is (= "ping" (-> @!seen :params :message :parts first :text))))

        (testing "opaque :metadata rides through to the wire verbatim"
          ;; This is the channel the Phase-3 call chain travels on.
          (client/send-message! peer "x" :metadata {"ai.brainyard/call-depth" 2})
          (is (= 2 (get-in @!seen [:params :message :metadata
                                   (keyword "ai.brainyard/call-depth")]))))))))

(deftest rpc-error-mapping-test
  (testing "a JSON-RPC error becomes a brainyard error map with :error-key"
    (with-stub [s {"/a2a" (fn [ex]
                            (respond! ex 200
                                      (a2a/encode (a2a/error-response
                                                   1 -32001 "Task not found"))))}]
      (let [peer (client/make-peer {:name "stub" :url (str (:base-url s) "/a2a")
                                    :card (card-for (:base-url s))})
            out  (client/get-task peer "nope")]
        (is (some? (:error out)))
        (is (= :task-not-found (:error-key out))))))

  (testing "a 401 produces a credentials hint"
    (with-stub [s {"/a2a" (fn [ex] (respond! ex 401 "{}"))}]
      (let [peer (client/make-peer
                  {:name "stub" :url (str (:base-url s) "/a2a")
                   :card (assoc (card-for (:base-url s))
                                :securitySchemes {:x {:type "http"}})})
            out  (client/send-message! peer "ping")]
        (is (str/includes? (:error out) "401"))
        (is (str/includes? (:error out) "requires authentication")))))

  (testing "a 500 is reported with the server's body snippet"
    (with-stub [s {"/a2a" (fn [ex] (respond! ex 500 "boom detail"))}]
      (let [peer (client/make-peer {:name "stub" :url (str (:base-url s) "/a2a")
                                    :card (card-for (:base-url s))})]
        (is (str/includes? (:error (client/send-message! peer "ping")) "boom detail")))))

  (testing "a non-JSON-RPC 200 body is reported, not silently accepted"
    (with-stub [s {"/a2a" (fn [ex] (respond! ex 200 "{\"unexpected\":true}"))}]
      (let [peer (client/make-peer {:name "stub" :url (str (:base-url s) "/a2a")
                                    :card (card-for (:base-url s))})]
        (is (some? (:error (client/send-message! peer "ping"))))))))

(deftest auth-header-reaches-the-server-test
  (let [!auth (atom nil)]
    (with-stub [s {"/a2a" (fn [ex]
                            (reset! !auth (.getFirst (.getRequestHeaders ex)
                                                     "Authorization"))
                            (respond! ex 200 (jsonrpc-result
                                              1 {:messageId "m" :role "agent"
                                                 :kind "message"
                                                 :parts [{:kind "text" :text "ok"}]})))}]
      (let [peer (client/make-peer {:name "stub" :url (str (:base-url s) "/a2a")
                                    :card (card-for (:base-url s))
                                    :auth "sk-token"})]
        (client/send-message! peer "ping")
        (testing "the bearer credential is sent"
          (is (= "Bearer sk-token" @!auth)))))))

(deftest version-header-test
  (let [!ver (atom nil)]
    (with-stub [s {"/a2a" (fn [ex]
                            (reset! !ver (.getFirst (.getRequestHeaders ex)
                                                    a2a/VERSION_HEADER))
                            (respond! ex 200 (jsonrpc-result 1 {:messageId "m"
                                                                :role "agent"
                                                                :parts []})))}]
      (let [peer (client/make-peer {:name "stub" :url (str (:base-url s) "/a2a")
                                    :card (card-for (:base-url s))})]
        (client/send-message! peer "ping")
        (testing "the A2A-Version service parameter is sent on every request"
          (is (= a2a/PROTOCOL_VERSION @!ver)))))))

;; =============================================================================
;; SSE
;; =============================================================================

(defn- collect-stream
  "Open an SSE subscription and collect payloads until `:on-close` fires or
   `timeout-ms` elapses. Returns {:events [...] :closed? bool :handle h}."
  [peer text timeout-ms]
  (let [!events (atom [])
        !closed (atom false)
        !errors (atom [])
        handle  (client/stream-message!
                 peer text
                 {:on-event (fn [p] (swap! !events conj p))
                  :on-error (fn [e] (swap! !errors conj e))
                  :on-close (fn [] (reset! !closed true))})
        deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (when (and (not @!closed) (< (System/currentTimeMillis) deadline))
        (Thread/sleep (long 20))
        (recur)))
    {:events @!events :closed? @!closed :errors @!errors :handle handle}))

(deftest sse-round-trip-test
  (with-stub [s {"/a2a"
                 (fn [ex]
                   (.set (.getResponseHeaders ex) "Content-Type" "text/event-stream")
                   (.sendResponseHeaders ex 200 0)
                   (with-open [os (.getResponseBody ex)]
                     (doseq [frame [(sse-frame 1 {:taskId "t-1" :kind "status-update"
                                                  :status {:state "working"
                                                           :message {:messageId "m"
                                                                     :role "agent"
                                                                     :parts [{:kind "text"
                                                                              :text "thinking"}]}}})
                                    ": keep-alive\n\n"
                                    (sse-frame 1 {:taskId "t-1" :kind "artifact-update"
                                                  :artifact {:artifactId "a-1"
                                                             :name "out.md"
                                                             :parts [{:kind "text"
                                                                      :text "body"}]}})
                                    (sse-frame 1 {:taskId "t-1" :kind "status-update"
                                                  :final true
                                                  :status {:state "completed"}})]]
                       (.write os (.getBytes ^String frame StandardCharsets/UTF_8))
                       (.flush os))))}]
    (let [peer (client/make-peer {:name "stub" :url (str (:base-url s) "/a2a")
                                  :card (card-for (:base-url s) :streaming true)})
          {:keys [events closed? errors]} (collect-stream peer "go" 5000)]

      (testing "every frame is delivered, keep-alives excluded"
        (is (= 3 (count events)) (str "got: " (pr-str events))))

      (testing "frames decode to their A2A objects"
        (is (= "working" (get-in (first events) [:result :status :state])))
        (is (= "a-1" (get-in (second events) [:result :artifact :artifactId])))
        (is (= "completed" (get-in (last events) [:result :status :state]))))

      (testing "on-close fires when the server ends the stream"
        (is closed?))

      (testing "an orderly EOF is NOT reported as an error"
        (is (empty? errors))))))

(deftest sse-stop-test
  (let [!served (atom 0)]
    (with-stub [s {"/a2a"
                   (fn [ex]
                     (.set (.getResponseHeaders ex) "Content-Type" "text/event-stream")
                     (.sendResponseHeaders ex 200 0)
                     (with-open [os (.getResponseBody ex)]
                       ;; An effectively endless stream — only `stop!` ends it.
                       (dotimes [_ 2000]
                         (.write os (.getBytes ^String
                                     (sse-frame 1 {:taskId "t" :kind "status-update"
                                                   :status {:state "working"}})
                                               StandardCharsets/UTF_8))
                         (.flush os)
                         (swap! !served inc)
                         (Thread/sleep (long 5)))))}]
      (let [peer (client/make-peer {:name "stub" :url (str (:base-url s) "/a2a")
                                    :card (card-for (:base-url s) :streaming true)})
            !n     (atom 0)
            !closed (atom false)
            handle (client/stream-message!
                    peer "go"
                    {:on-event (fn [_] (swap! !n inc))
                     :on-close (fn [] (reset! !closed true))})]
        (testing "the subscription reports itself running"
          (Thread/sleep (long 200))
          (is (pos? @!n) "should have received frames")
          (is ((:running? handle))))

        (testing "stop! halts the reader and fires on-close"
          ((:stop! handle))
          (Thread/sleep (long 300))
          (is (not ((:running? handle))))
          (is @!closed)
          (let [n-at-stop @!n]
            (Thread/sleep (long 200))
            (is (= n-at-stop @!n) "no events after stop!")))))))

(deftest sse-refused-when-not-advertised-test
  (testing "streaming is refused up front on a non-streaming card"
    ;; Cheaper than a round trip that returns UnsupportedOperationError
    ;; telling us what the card already said.
    (with-stub [s {"/a2a" (fn [ex] (respond! ex 200 "{}"))}]
      (let [peer (client/make-peer {:name "stub" :url (str (:base-url s) "/a2a")
                                    :card (card-for (:base-url s) :streaming false)})
            out  (client/stream-message! peer "go" {})]
        (is (some? (:error out)))
        (is (str/includes? (:error out) "streaming"))))))

(deftest sse-http-error-test
  (testing "a non-200 on the stream endpoint reaches on-error"
    (with-stub [s {"/a2a" (fn [ex] (respond! ex 403 "nope"))}]
      (let [peer (client/make-peer {:name "stub" :url (str (:base-url s) "/a2a")
                                    :card (card-for (:base-url s) :streaming true)})
            !errs (atom [])
            !closed (atom false)]
        (client/stream-message! peer "go"
                                {:on-event (fn [_] nil)
                                 :on-error (fn [e] (swap! !errs conj e))
                                 :on-close (fn [] (reset! !closed true))})
        (Thread/sleep (long 500))
        (is (= 1 (count @!errs)))
        (is (str/includes? (:error (first @!errs)) "403"))
        (is @!closed "on-close must still fire after an error")))))

(deftest sse-thread-is-daemon-test
  (testing "the reader thread is a daemon so it cannot keep the JVM alive"
    ;; A subscription outliving its caller must never be the reason `by`
    ;; will not quit.
    (with-stub [s {"/a2a"
                   (fn [ex]
                     (.set (.getResponseHeaders ex) "Content-Type" "text/event-stream")
                     (.sendResponseHeaders ex 200 0)
                     (with-open [os (.getResponseBody ex)]
                       (dotimes [_ 200]
                         (.write os (.getBytes ^String
                                     (sse-frame 1 {:taskId "t" :kind "status-update"
                                                   :status {:state "working"}})
                                               StandardCharsets/UTF_8))
                         (.flush os)
                         (Thread/sleep (long 10)))))}]
      (let [peer (client/make-peer {:name "stub" :url (str (:base-url s) "/a2a")
                                    :card (card-for (:base-url s) :streaming true)})
            handle (client/stream-message! peer "go" {:on-event (fn [_] nil)})]
        (Thread/sleep (long 150))
        (let [threads (keys (Thread/getAllStackTraces))
              sse     (filter #(str/starts-with? (.getName ^Thread %) "a2a-sse-") threads)]
          (is (seq sse) "the reader thread should be findable by name")
          (is (every? #(.isDaemon ^Thread %) sse)))
        ((:stop! handle))))))

;; =============================================================================
;; Task polling — the primitive the Phase-4 executor is built on
;; =============================================================================

(deftest task-polling-test
  (let [!calls (atom 0)]
    (with-stub [s {"/a2a" (fn [ex]
                            (let [n (swap! !calls inc)]
                              (respond! ex 200
                                        (jsonrpc-result
                                         1 {:id "t-1" :kind "task"
                                            :status {:state (if (< n 3) "working" "completed")}}))))}]
      (let [peer (client/make-peer {:name "stub" :url (str (:base-url s) "/a2a")
                                    :card (card-for (:base-url s))})]
        (testing "task-state polls until the state changes"
          (is (= :working (:state (client/task-state peer "t-1"))))
          (is (= :working (:state (client/task-state peer "t-1"))))
          (is (= :completed (:state (client/task-state peer "t-1")))))))))

(deftest cancel-test
  (with-stub [s {"/a2a" (fn [ex]
                          (respond! ex 200
                                    (jsonrpc-result 1 {:id "t-1" :kind "task"
                                                       :status {:state "canceled"}})))}]
    (let [peer (client/make-peer {:name "stub" :url (str (:base-url s) "/a2a")
                                  :card (card-for (:base-url s))})
          {:keys [task error]} (client/cancel-task! peer "t-1")]
      (testing "cancel round-trips and reports the one-L wire state"
        (is (nil? error))
        (is (= "canceled" (get-in task [:status :state])))
        (is (= :canceled (a2a/state->kw (get-in task [:status :state]))))))))
