;; Copyright (c) 2024-2026 Grumatic, Inc.
;; SPDX-License-Identifier: MIT
;; Licensed under the MIT License. See LICENSE at the repository root.

(ns ai.brainyard.agent.a2a-task-test
  "Tests for Phase 4: the `:a2a` task executor and the streaming ask.

   Driven against a real in-process HTTP server on an ephemeral loopback
   port, because the behaviours that matter here — poll throttling, state
   promotion, SSE frames becoming hook events — only exist over a socket.

   The interrupted-state tests are the ones to keep: `input-required` and
   `auth-required` are NOT terminal, and treating them as such abandons a
   task the peer is still holding open for us."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.brainyard.a2a.interface :as a2a]
            [ai.brainyard.a2a-client.interface :as a2a-client]
            [ai.brainyard.agent.common.a2a :as a2a-cmd]
            [ai.brainyard.agent.core.agent :as agent-core]
            [ai.brainyard.agent.core.hooks :as hooks]
            [ai.brainyard.agent.core.protocol :as proto]
            [ai.brainyard.agent.core.remote-agent :as remote]
            [ai.brainyard.agent.task.executor :as executor]
            [ai.brainyard.agent.task.protocol :as tp])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

;; =============================================================================
;; Stub server
;; =============================================================================

(defn- respond! [^HttpExchange ex status ^String body & {:keys [content-type]}]
  (let [bs (.getBytes body StandardCharsets/UTF_8)]
    (.set (.getResponseHeaders ex) "Content-Type" (or content-type "application/json"))
    (.sendResponseHeaders ex status (alength bs))
    (with-open [os (.getResponseBody ex)] (.write os bs))))

(defn- handler ^HttpHandler [f]
  (reify HttpHandler
    (handle [_ ex]
      (try (f ex)
           (catch Throwable _ nil)
           (finally (.close ^HttpExchange ex))))))

(defn- start-stub! [routes]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        port   (.getPort (.getAddress server))
        stub   {:server server :port port
                :base-url (str "http://127.0.0.1:" port)}]
    (doseq [[path f] (if (fn? routes) (routes stub) routes)]
      (.createContext server ^String path (handler f)))
    (.setExecutor server nil)
    (.start server)
    stub))

(defmacro with-stub [[binding routes] & body]
  `(let [~binding (start-stub! ~routes)]
     (try ~@body (finally (.stop ^HttpServer (:server ~binding) 0)))))

(defn- card-for [base-url & {:keys [streaming]}]
  {:name "stub-peer"
   :url (str base-url "/a2a")
   :protocolVersion a2a/PROTOCOL_VERSION
   :capabilities {:streaming (boolean streaming)}
   :skills [{:id "echo" :name "Echo"}]})

(defn- jsonrpc-result [result] (a2a/encode (a2a/response 1 result)))
(defn- sse-frame [result] (str "data: " (jsonrpc-result result) "\n\n"))

(defn- task-obj [state & {:keys [text artifacts]}]
  (cond-> {:id "t-1" :kind "task" :contextId "c-1"
           :status (cond-> {:state state}
                     text (assoc :message {:messageId "m" :role "agent"
                                           :parts [{:kind "text" :text text}]}))}
    artifacts (assoc :artifacts artifacts)))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn clean [f]
  (a2a-client/reset-peers!)
  (try (f)
       (finally (a2a-client/reset-peers!)
                (agent-core/reset-agent-registry!))))

(use-fixtures :each clean)

(defn- connect-stub! [stub & {:keys [streaming]}]
  (a2a-client/register-peer!
   (a2a-client/make-peer {:name "b"
                          :url (str (:base-url stub) "/a2a")
                          :card (card-for (:base-url stub) :streaming streaming)})))

(defn- make-session []
  (atom {:session-id "agt-test" :user-id "u" :messages [] :total-turns 0 :data {}}))

(defn- make-parent [!session]
  (agent-core/map->Agent
   {:agent-id :router-agent/test-parent
    :!state   (atom {:status :idle
                     :lifecycle {:owner nil :answers 0
                                 :created-at (System/currentTimeMillis)}
                     :runtime {}})
    :!session !session}))

(defn- make-remote [!session parent]
  (remote/create {:agent-id :a2a$b$echo/test-remote
                  :peer-name "b" :skill-id "echo"
                  :parent-agent parent :!session !session
                  :remote-agent-id "https://stub/a2a#echo"}))

(defn- a-task [job-config]
  {:id :task-test :name "remote" :job-type :a2a :job-config job-config})

;; =============================================================================
;; Executor — happy path
;; =============================================================================

(deftest executor-promotes-on-terminal-state-test
  (let [!calls (atom 0)]
    (with-stub [s {"/a2a" (fn [ex]
                            (let [n (swap! !calls inc)]
                              (respond! ex 200
                                        (jsonrpc-result
                                         (task-obj (if (< n 3) "working" "completed")
                                                   :text "the answer")))))}]
      (connect-stub! s)
      (let [out    (atom [])
            r      (tp/execute-job (executor/->A2ATaskJobExecutor)
                                   (a-task {:peer-name "b" :remote-task-id "t-1"
                                            :poll-interval-ms 1})
                                   #(swap! out conj %))]
        (testing "it detaches rather than blocking the pool thread"
          (is (= :detached (:status r)))
          (is (fn? (:on-poll r)))
          (is (fn? (:on-cancel r))))

        (testing "polling reports still-running until the peer finishes"
          (is (= tp/still-running ((:on-poll r))))
          (Thread/sleep (long 5))
          (is (= tp/still-running ((:on-poll r)))))

        (testing "a terminal state promotes with the answer"
          (Thread/sleep (long 5))
          (let [final ((:on-poll r))]
            (is (map? final))
            (is (= :completed (get-in final [:result :state])))
            (is (= "the answer" (get-in final [:result :answer])))))

        (testing "state transitions are logged to task output"
          (is (some #(str/includes? % "state: working") @out))
          (is (some #(str/includes? % "state: completed") @out)))))))

(deftest executor-throttles-polling-test
  (let [!calls (atom 0)]
    (with-stub [s {"/a2a" (fn [ex]
                            (swap! !calls inc)
                            (respond! ex 200 (jsonrpc-result (task-obj "working"))))}]
      (connect-stub! s)
      (let [r (tp/execute-job (executor/->A2ATaskJobExecutor)
                              (a-task {:peer-name "b" :remote-task-id "t-1"
                                       :poll-interval-ms 10000})
                              (fn [_]))]
        (testing "rapid on-poll calls do NOT each hit the peer"
          ;; The manager's watcher fires ~every 250ms. Four HTTP requests
          ;; per second per task at a third party would get us rate-limited.
          (dotimes [_ 20] ((:on-poll r)))
          (is (= 1 @!calls)
              (str "expected 1 network poll within the interval, got " @!calls)))))))

;; =============================================================================
;; Executor — the states that are easy to get wrong
;; =============================================================================

(deftest interrupted-states-do-not-terminate-the-task-test
  (doseq [[state expect-word] [["input-required" "input"]
                               ["auth-required" "credentials"]]]
    (let [!calls (atom 0)]
      (with-stub [s {"/a2a" (fn [ex]
                              (swap! !calls inc)
                              (respond! ex 200 (jsonrpc-result (task-obj state))))}]
        (connect-stub! s)
        (let [out (atom [])
              r   (tp/execute-job (executor/->A2ATaskJobExecutor)
                                  (a-task {:peer-name "b" :remote-task-id "t-1"
                                           :poll-interval-ms 1})
                                  #(swap! out conj %))]
          (testing (str state " keeps the task RUNNING, not failed")
            ;; The peer is holding the task open for us; promoting it to a
            ;; terminal status would abandon resumable work.
            (Thread/sleep (long 5))
            (is (= tp/still-running ((:on-poll r))))
            (Thread/sleep (long 5))
            (is (= tp/still-running ((:on-poll r)))))

          (testing (str state " tells the user what the peer wants")
            (is (some #(str/includes? % expect-word) @out)
                (str "output should mention " expect-word ": " (pr-str @out))))

          (testing "the advice is announced ONCE, not on every poll"
            (is (= 1 (count (filter #(str/includes? % "awaiting") @out))))))))))

(deftest failed-and-rejected-become-errors-test
  (doseq [state ["failed" "rejected"]]
    (with-stub [s {"/a2a" (fn [ex]
                            (respond! ex 200 (jsonrpc-result
                                              (task-obj state :text "nope"))))}]
      (connect-stub! s)
      (let [r (tp/execute-job (executor/->A2ATaskJobExecutor)
                              (a-task {:peer-name "b" :remote-task-id "t-1"
                                       :poll-interval-ms 1})
                              (fn [_]))]
        (Thread/sleep (long 5))
        (let [final ((:on-poll r))]
          (testing (str state " is an error, carrying the peer's reason")
            (is (some? (:error final)))
            (is (str/includes? (:error final) state))
            (is (str/includes? (:error final) "nope")
                "a rejection's reason must not be swallowed")))))))

(deftest canceled-is-terminal-but-not-an-error-test
  (with-stub [s {"/a2a" (fn [ex]
                          (respond! ex 200 (jsonrpc-result (task-obj "canceled"))))}]
    (connect-stub! s)
    (let [r (tp/execute-job (executor/->A2ATaskJobExecutor)
                            (a-task {:peer-name "b" :remote-task-id "t-1"
                                     :poll-interval-ms 1})
                            (fn [_]))]
      (Thread/sleep (long 5))
      (let [final ((:on-poll r))]
        (is (nil? (:error final)))
        (is (= :canceled (get-in final [:result :state])))))))

(deftest transient-poll-failure-does-not-fail-the-task-test
  (let [!calls (atom 0)]
    (with-stub [s {"/a2a" (fn [ex]
                            (let [n (swap! !calls inc)]
                              (if (< n 3)
                                (respond! ex 503 "unavailable")
                                (respond! ex 200 (jsonrpc-result
                                                  (task-obj "completed" :text "ok"))))))}]
      (connect-stub! s)
      (let [out (atom [])
            r   (tp/execute-job (executor/->A2ATaskJobExecutor)
                                (a-task {:peer-name "b" :remote-task-id "t-1"
                                         :poll-interval-ms 1})
                                #(swap! out conj %))]
        (testing "a 503 keeps polling instead of failing the task"
          ;; The peer may simply be restarting; the user can task$cancel if
          ;; it persists.
          (Thread/sleep (long 5))
          (is (= tp/still-running ((:on-poll r))))
          (Thread/sleep (long 5))
          (is (= tp/still-running ((:on-poll r)))))

        (testing "it recovers once the peer comes back"
          (Thread/sleep (long 5))
          (is (= :completed (get-in ((:on-poll r)) [:result :state]))))

        (testing "the failure is reported once, not on every retry"
          (is (= 1 (count (filter #(str/includes? % "poll failed") @out)))))))))

(deftest missing-peer-and-task-id-are-errors-test
  (testing "an unconnected peer fails fast"
    (let [r (tp/execute-job (executor/->A2ATaskJobExecutor)
                            (a-task {:peer-name "nope" :remote-task-id "t-1"})
                            (fn [_]))]
      (is (some? (:error r)))
      (is (not= :detached (:status r)))))

  (testing "a missing remote-task-id fails fast"
    (with-stub [s {"/a2a" (fn [ex] (respond! ex 200 "{}"))}]
      (connect-stub! s)
      (is (some? (:error (tp/execute-job (executor/->A2ATaskJobExecutor)
                                         (a-task {:peer-name "b"})
                                         (fn [_]))))))))

(deftest cancel-calls-the-peer-test
  (let [!cancelled (atom nil)]
    (with-stub [s {"/a2a" (fn [ex]
                            (let [body (slurp (.getRequestBody ex) :encoding "UTF-8")
                                  msg  (a2a/decode body)]
                              (when (= "tasks/cancel" (:method msg))
                                (reset! !cancelled (get-in msg [:params :id])))
                              (respond! ex 200 (jsonrpc-result (task-obj "canceled")))))}]
      (connect-stub! s)
      (let [r (tp/execute-job (executor/->A2ATaskJobExecutor)
                              (a-task {:peer-name "b" :remote-task-id "t-1"
                                       :poll-interval-ms 1})
                              (fn [_]))]
        ((:on-cancel r))
        (testing "on-cancel issues tasks/cancel against the peer"
          (is (= "t-1" @!cancelled)))))))

;; =============================================================================
;; Streaming ask
;; =============================================================================

(defn- streaming-route [frames]
  (fn [^HttpExchange ex]
    (.set (.getResponseHeaders ex) "Content-Type" "text/event-stream")
    (.sendResponseHeaders ex 200 0)
    (with-open [os (.getResponseBody ex)]
      (doseq [f frames]
        (.write os (.getBytes ^String f StandardCharsets/UTF_8))
        (.flush os)))))

(deftest streaming-ask-fires-hooks-and-returns-the-answer-test
  (with-stub [s {"/a2a" (streaming-route
                         [(sse-frame {:taskId "t-1" :kind "status-update"
                                      :status {:state "working"
                                               :message {:messageId "m" :role "agent"
                                                         :parts [{:kind "text"
                                                                  :text "thinking… "}]}}})
                          (sse-frame {:taskId "t-1" :kind "status-update"
                                      :status {:state "working"
                                               :message {:messageId "m" :role "agent"
                                                         :parts [{:kind "text"
                                                                  :text "thinking… done"}]}}})
                          (sse-frame {:taskId "t-1" :kind "status-update"
                                      :final true :status {:state "completed"}})])}]
    (connect-stub! s :streaming true)
    (let [!chunks (atom [])
          !states (atom [])]
      (hooks/register-hook! :agent.dspy-action/chunk ::test-chunk
                            #(swap! !chunks conj (:chunk %)) :source ::t)
      (hooks/register-hook! :a2a/task-state ::test-state
                            #(swap! !states conj (:state %)) :source ::t)
      (try
        (let [!s' (make-session)
              ra  (make-remote !s' (make-parent !s'))
              out (remote/ask-streaming ra (a2a-client/get-peer "b") "go"
                                        {:timeout-ms 5000})]
          (testing "the terminal answer comes back"
            (is (nil? (:error out)))
            (is (= :completed (:state out)))
            (is (= "thinking… done" (:answer out))))

          (testing "streamed text fired real brainyard chunk hooks"
            ;; This is what makes the answer render progressively in the TUI
            ;; with no new UI code.
            (is (seq @!chunks))
            (is (= "thinking… " (first @!chunks)))
            ;; The peer resent the WHOLE message ("thinking… done") rather
            ;; than a delta. Only the new suffix may be emitted, or the
            ;; transcript would read "thinking… thinking… done".
            (is (= "done" (second @!chunks))
                "cumulative resends must be diffed, not re-emitted whole")
            (is (= "thinking… done" (str/join @!chunks))
                "the chunks must reassemble into exactly the final text"))

          (testing "state transitions fired too"
            (is (some #{:working} @!states))
            (is (some #{:completed} @!states))))
        (finally (hooks/unregister-source! ::t))))))

(deftest streaming-interrupted-is-reported-as-paused-test
  (with-stub [s {"/a2a" (streaming-route
                         [(sse-frame {:taskId "t-1" :kind "status-update"
                                      :status {:state "input-required"
                                               :message {:messageId "m" :role "agent"
                                                         :parts [{:kind "text"
                                                                  :text "Which file?"}]}}})])}]
    (connect-stub! s :streaming true)
    (let [!s' (make-session)
          ra  (make-remote !s' (make-parent !s'))
          out (remote/ask-streaming ra (a2a-client/get-peer "b") "go"
                                    {:timeout-ms 5000})]
      (testing "an interrupted stream resolves as paused, not completed"
        (is (= :input-required (:state out)))
        (is (nil? (:error out)))))))

(deftest streaming-truncation-is-not-silent-success-test
  (with-stub [s {"/a2a" (streaming-route
                         ;; A working frame, then the server just closes.
                         [(sse-frame {:taskId "t-1" :kind "status-update"
                                      :status {:state "working"
                                               :message {:messageId "m" :role "agent"
                                                         :parts [{:kind "text"
                                                                  :text "partial"}]}}})])}]
    (connect-stub! s :streaming true)
    (let [!s' (make-session)
          ra  (make-remote !s' (make-parent !s'))
          out (remote/ask-streaming ra (a2a-client/get-peer "b") "go"
                                    {:timeout-ms 5000})]
      (testing "a stream that ends without a terminal frame reports an error"
        ;; Returning the partial text as if it were the answer is how a
        ;; caller silently acts on a truncated result.
        (is (some? (:error out)))
        (is (str/includes? (:error out) "terminal")))

      (testing "the partial text is still carried, not thrown away"
        (is (= "partial" (:answer out)))))))

(deftest streaming-artifact-reaches-the-artifact-hook-test
  (with-stub [s {"/a2a" (streaming-route
                         [(sse-frame {:taskId "t-1" :kind "artifact-update"
                                      :artifact {:artifactId "a-1" :name "out.md"
                                                 :parts [{:kind "text" :text "body"}]}
                                      :lastChunk true})
                          (sse-frame {:taskId "t-1" :kind "status-update"
                                      :final true :status {:state "completed"}})])}]
    (connect-stub! s :streaming true)
    (let [!seen (atom [])]
      (hooks/register-hook! :a2a/artifact ::test-artifact
                            #(swap! !seen conj (select-keys % [:artifact-id :name :text]))
                            :source ::t)
      (try
        (let [!s' (make-session)
              ra  (make-remote !s' (make-parent !s'))]
          (remote/ask-streaming ra (a2a-client/get-peer "b") "go" {:timeout-ms 5000})
          (testing "the artifact frame reached the hooks bus"
            ;; core/remote_agent fires; common/a2a subscribes and persists.
            ;; Firing rather than persisting inline is what keeps core from
            ;; requiring common.
            (is (= [{:artifact-id "a-1" :name "out.md" :text "body"}] @!seen))))
        (finally (hooks/unregister-source! ::t))))))

(deftest streaming-refused-when-card-says-no-test
  (with-stub [s {"/a2a" (fn [ex] (respond! ex 200 "{}"))}]
    (connect-stub! s :streaming false)
    (let [!s' (make-session)
          ra  (make-remote !s' (make-parent !s'))
          out (remote/ask-streaming ra (a2a-client/get-peer "b") "go" {:timeout-ms 2000})]
      (testing "a non-streaming card is refused before any request"
        (is (some? (:error out)))
        (is (str/includes? (:error out) "streaming"))))))

;; =============================================================================
;; process/2 chooses its transport
;; =============================================================================

(deftest process-falls-back-to-blocking-send-test
  (with-stub [s {"/a2a" (fn [ex]
                          (respond! ex 200 (jsonrpc-result
                                            (task-obj "completed" :text "blocking answer"))))}]
    (connect-stub! s :streaming false)
    (let [!s' (make-session)
          ra  (make-remote !s' (make-parent !s'))
          r   (proto/process ra "hi" nil)]
      (testing "a non-streaming peer is asked with message/send"
        (is (= "blocking answer" (:answer r)))
        (is (false? (get-in r [:result :streamed])))))))

(deftest process-marks-interrupted-answers-test
  (with-stub [s {"/a2a" (fn [ex]
                          (respond! ex 200 (jsonrpc-result
                                            (task-obj "input-required" :text "Which one?"))))}]
    (connect-stub! s :streaming false)
    (let [!s' (make-session)
          ra  (make-remote !s' (make-parent !s'))
          r   (proto/process ra "hi" nil)]
      (testing "the answer says the remote task PAUSED"
        (is (str/includes? (:answer r) "PAUSED"))
        (is (str/includes? (:answer r) "has NOT finished"))
        (is (str/includes? (:answer r) "agent-registry$ask"))))))

(deftest process-remembers-context-for-follow-ups-test
  (with-stub [s {"/a2a" (fn [ex]
                          (respond! ex 200 (jsonrpc-result
                                            (task-obj "completed" :text "ok"))))}]
    (connect-stub! s :streaming false)
    (let [!s' (make-session)
          ra  (make-remote !s' (make-parent !s'))]
      (proto/process ra "hi" nil)
      (testing "the peer's contextId is retained so a follow-up continues it"
        (is (= "c-1" (remote/context-id ra)))
        (is (= "t-1" (remote/last-task-id ra)))))))
